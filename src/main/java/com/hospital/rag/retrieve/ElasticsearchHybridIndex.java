package com.hospital.rag.retrieve;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 原生混合检索索引：同一索引内同时保存文本与向量，
 * 由 ES 在一次查询中完成 BM25 召回、kNN 召回与结果融合。
 *
 * <p>两种融合方式：</p>
 * <ul>
 *   <li>{@code rrf}（默认）：ES 8.8+ 的 {@code rank.rrf}，在协调节点按排名倒数融合，
 *       无需分数归一化，对两路分数量纲不敏感；</li>
 *   <li>{@code knn}：{@code query} 与 {@code knn} 并列，由 ES 将两路 {@code _score} 加权求和，
 *       可通过 {@code text-boost} / {@code knn-boost} 调整权重。</li>
 * </ul>
 *
 * <p>仅在 {@code rag.hybrid.backend=elasticsearch} 时生效。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.hybrid.backend", havingValue = "elasticsearch")
public class ElasticsearchHybridIndex implements HybridSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHybridIndex.class);

    private static final String EMBEDDING_FIELD = "embedding";

    private final ElasticsearchClient client;
    private final RagProperties.Hybrid config;
    private final String indexName;

    public ElasticsearchHybridIndex(ElasticsearchClient client, RagProperties properties) {
        this.client = client;
        this.config = properties.getHybrid();
        this.indexName = config.getIndexName();
    }

    /** 启动时确保索引存在（ES 不可用时不阻断应用启动）。 */
    @PostConstruct
    void initialize() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                log.info("Elasticsearch 混合索引已存在: {}", indexName);
            } else {
                client.indices().create(c -> c.index(indexName).withJson(new StringReader(indexDefinition())));
                log.info("已创建 Elasticsearch 混合索引: {} (dims={}, analyzer={}, fusion={})",
                        indexName, config.getDimension(), config.getAnalyzer(), config.getFusion());
            }
        } catch (Exception e) {
            log.error("初始化 Elasticsearch 混合索引失败（请确认 ES 可用）: {}", e.getMessage());
        }
    }

    /** 索引定义：文本字段用于 BM25，{@code embedding} 用于 kNN。 */
    private String indexDefinition() {
        return "{"
                + "\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0,"
                + "\"analysis\":{\"analyzer\":{\"default\":{\"type\":\"" + config.getAnalyzer() + "\"}}}},"
                + "\"mappings\":{\"properties\":{"
                + "\"chunkId\":{\"type\":\"keyword\"},"
                + "\"documentId\":{\"type\":\"keyword\"},"
                + "\"documentTitle\":{\"type\":\"text\"},"
                + "\"content\":{\"type\":\"text\"},"
                + "\"chunkIndex\":{\"type\":\"integer\"},"
                + "\"departments\":{\"type\":\"keyword\"},"
                + "\"roles\":{\"type\":\"keyword\"},"
                + "\"sensitivityLevel\":{\"type\":\"integer\"},"
                + "\"publicAccess\":{\"type\":\"boolean\"},"
                + "\"metadata\":{\"type\":\"object\",\"enabled\":false},"
                + "\"embedding\":{\"type\":\"dense_vector\",\"dims\":" + config.getDimension()
                + ",\"index\":true,\"similarity\":\"cosine\"}"
                + "}}}";
    }

    @Override
    public String name() {
        return "elasticsearch:" + indexName;
    }

    /** 批量写入片段及其向量。 */
    @Override
    public void indexAll(Collection<Chunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty() || embeddings == null || embeddings.isEmpty()) {
            return;
        }
        assertDimension(embeddings);
        List<Chunk> chunkList = new ArrayList<>(chunks);
        int n = Math.min(chunkList.size(), embeddings.size());
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (int i = 0; i < n; i++) {
                Chunk chunk = chunkList.get(i);
                HybridDoc doc = toDoc(chunk, embeddings.get(i));
                builder.operations(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(chunk.getId())
                        .document(doc)));
            }
            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                String reason = response.items().stream()
                        .filter(item -> item.error() != null)
                        .findFirst()
                        .map(item -> item.error().reason())
                        .orElse("unknown");
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .limit(5)
                        .forEach(item -> log.warn("混合索引部分文档写入失败: {}", item.error().reason()));
                // 写入失败必须让调用方感知：静默返回成功会让知识库看起来有数据、实际检索为空
                throw new IllegalStateException("混合索引写入失败: " + reason);
            }
            client.indices().refresh(r -> r.index(indexName));
            log.info("混合索引写入完成: {} 条", n);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("混合索引批量写入失败: {}", e.getMessage(), e);
            throw new IllegalStateException("混合索引批量写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入前校验向量维度。
     *
     * <p>{@code dense_vector.dims} 在索引创建时固定，模型换过之后维度对不上会被 ES 拒写。
     * 本地内存向量库不校验维度，因此这里做一次前置检查，把「静默失败」变成明确报错。</p>
     */
    private void assertDimension(List<float[]> embeddings) {
        for (float[] vector : embeddings) {
            if (vector == null) {
                continue;
            }
            if (vector.length != config.getDimension()) {
                throw new IllegalStateException(
                        "embedding 维度与索引不一致：模型输出 " + vector.length
                                + " 维，但 rag.hybrid.dimension=" + config.getDimension()
                                + "。请修正配置并删除索引 " + indexName + " 后重启以重建");
            }
            return;
        }
    }

    /**
     * 单次查询完成混合检索。
     *
     * @param queryText   查询文本（走 BM25）
     * @param queryVector 查询向量（走 kNN）
     * @param topK        返回条数
     * @return 融合后的结果
     */
    @Override
    public List<ScoredChunk> search(String queryText, float[] queryVector, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (topK <= 0 || queryText == null || queryText.isBlank()
                || queryVector == null || queryVector.length == 0) {
            return results;
        }

        boolean useRrf = "rrf".equalsIgnoreCase(config.getFusion());
        long windowSize = Math.max(config.getWindowSize(), topK);
        long knnK = Math.max(config.getKnnK(), topK);
        long numCandidates = Math.max(config.getNumCandidates(), knnK);

        try {
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(indexName)
                    .query(q -> q.multiMatch(m -> m
                            .query(queryText)
                            .fields("content^" + config.getTextBoost(), "documentTitle^1.5")))
                    .knn(k -> k
                            .field(EMBEDDING_FIELD)
                            .queryVector(toFloatList(queryVector))
                            .k(knnK)
                            .numCandidates(numCandidates)
                            .boost((float) config.getKnnBoost()))
                    .size(topK)
                    // 不回传向量字段，避免每次查询搬运大量浮点数
                    .source(s -> s.filter(f -> f.excludes(EMBEDDING_FIELD)));

            if (useRrf) {
                builder.rank(r -> r.rrf(rr -> rr
                        .rankConstant(config.getRankConstant())
                        .windowSize(windowSize)));
            }

            SearchResponse<HybridDoc> response = client.search(builder.build(), HybridDoc.class);
            for (Hit<HybridDoc> hit : response.hits().hits()) {
                HybridDoc doc = hit.source();
                if (doc == null) {
                    continue;
                }
                double score = hit.score() == null ? 0.0 : hit.score();
                results.add(ScoredChunk.builder()
                        .chunk(toChunk(doc))
                        .score(score)
                        .source(useRrf ? "hybrid-es-rrf" : "hybrid-es-knn")
                        .build());
            }
        } catch (Exception e) {
            log.error("Elasticsearch 混合检索失败: {}", e.getMessage(), e);
        }
        return results;
    }

    /** 索引内文档总数。 */
    @Override
    public int size() {
        try {
            return (int) client.count(c -> c.index(indexName)).count();
        } catch (Exception e) {
            log.warn("混合索引 count 失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 重建索引。 */
    @Override
    public void clear() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                client.indices().delete(d -> d.index(indexName));
            }
            client.indices().create(c -> c.index(indexName).withJson(new StringReader(indexDefinition())));
            log.info("已重建 Elasticsearch 混合索引: {}", indexName);
        } catch (Exception e) {
            log.error("重建混合索引失败: {}", e.getMessage(), e);
        }
    }

    // ---------- 转换 ----------

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    private HybridDoc toDoc(Chunk chunk, float[] embedding) {
        AccessControl acl = chunk.getAccessControl();
        return new HybridDoc(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getDocumentTitle(),
                chunk.getContent(),
                chunk.getIndex(),
                acl == null ? Collections.emptyList() : new ArrayList<>(acl.getDepartments()),
                acl == null ? Collections.emptyList() : new ArrayList<>(acl.getRoles()),
                acl == null ? 0 : acl.getSensitivityLevel(),
                acl != null && acl.isPublicAccess(),
                chunk.getMetadata(),
                toFloatList(embedding));
    }

    private Chunk toChunk(HybridDoc doc) {
        AccessControl acl = AccessControl.builder()
                .departments(doc.getDepartments() == null ? new HashSet<>() : new HashSet<>(doc.getDepartments()))
                .roles(doc.getRoles() == null ? new HashSet<>() : new HashSet<>(doc.getRoles()))
                .sensitivityLevel(doc.getSensitivityLevel() == null ? 0 : doc.getSensitivityLevel())
                .publicAccess(Boolean.TRUE.equals(doc.getPublicAccess()))
                .build();
        return Chunk.builder()
                .id(doc.getChunkId())
                .documentId(doc.getDocumentId())
                .documentTitle(doc.getDocumentTitle())
                .content(doc.getContent())
                .index(doc.getChunkIndex() == null ? 0 : doc.getChunkIndex())
                .accessControl(acl)
                .build();
    }

    /** 混合索引文档结构。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridDoc {
        private String chunkId;
        private String documentId;
        private String documentTitle;
        private String content;
        private Integer chunkIndex;
        private List<String> departments;
        private List<String> roles;
        private Integer sensitivityLevel;
        private Boolean publicAccess;
        private Map<String, Object> metadata;
        private List<Float> embedding;
    }
}
