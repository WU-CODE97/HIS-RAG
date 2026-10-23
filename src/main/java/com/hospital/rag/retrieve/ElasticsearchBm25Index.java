package com.hospital.rag.retrieve;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
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
 * 基于 Elasticsearch 的 BM25 实现。
 *
 * <p>由 ES 负责分词、倒排与 BM25 打分，本类只做文档映射与结果转换。
 * 仅在 {@code rag.bm25.backend=elasticsearch} 时生效。</p>
 *
 * <p><b>权限过滤</b>：ACL 字段（departments / roles / sensitivityLevel / publicAccess）一并写入索引，
 * 但过滤动作仍统一在 {@link Bm25Retriever} 的 Java 侧完成，保证与向量检索共享同一套权限语义。
 * 若数据量很大，可改为把权限条件下推到 ES 的 {@code bool} 查询中，减少无效回传。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.bm25.backend", havingValue = "elasticsearch")
public class ElasticsearchBm25Index implements Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchBm25Index.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final String analyzer;

    public ElasticsearchBm25Index(ElasticsearchClient client, RagProperties properties) {
        this.client = client;
        this.indexName = properties.getBm25().getIndexName();
        this.analyzer = properties.getBm25().getAnalyzer();
    }

    /** 启动时确保索引存在（ES 不可用时不阻断应用启动）。 */
    @PostConstruct
    void initialize() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                log.info("Elasticsearch 索引已存在: {}", indexName);
            } else {
                createIndex();
                log.info("已创建 Elasticsearch 索引: {} (analyzer={})", indexName, analyzer);
            }
        } catch (Exception e) {
            log.error("初始化 Elasticsearch 索引失败（请确认 ES 可用）: {}", e.getMessage());
        }
    }

    @Override
    public void index(Chunk chunk) {
        indexAll(Collections.singletonList(chunk));
    }

    @Override
    public void indexAll(Collection<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (Chunk chunk : chunks) {
                builder.operations(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(chunk.getId())
                        .document(toDoc(chunk))));
            }
            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .limit(5)
                        .forEach(item -> log.warn("部分文档写入失败: {}", item.error().reason()));
            }
            // 立即刷新，保证紧随其后的检索可见（生产环境可改为按 refresh_interval 异步刷新）
            client.indices().refresh(r -> r.index(indexName));
        } catch (Exception e) {
            log.error("Elasticsearch 批量写入失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredChunk> search(String query, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (query == null || query.isBlank() || topK <= 0) {
            return results;
        }
        try {
            SearchResponse<ChunkDoc> response = client.search(s -> s
                            .index(indexName)
                            .query(q -> q.multiMatch(m -> m
                                    .query(query)
                                    .fields("content^2.0", "documentTitle^1.5")))
                            .size(topK),
                    ChunkDoc.class);

            for (Hit<ChunkDoc> hit : response.hits().hits()) {
                ChunkDoc doc = hit.source();
                if (doc == null) {
                    continue;
                }
                double score = hit.score() == null ? 0.0 : hit.score();
                results.add(ScoredChunk.builder()
                        .chunk(toChunk(doc))
                        .score(score)
                        .bm25Score(score)
                        .source("bm25-es")
                        .build());
            }
        } catch (Exception e) {
            log.error("Elasticsearch 检索失败: {}", e.getMessage(), e);
        }
        return results;
    }

    @Override
    public int size() {
        try {
            return (int) client.count(c -> c.index(indexName)).count();
        } catch (Exception e) {
            log.warn("Elasticsearch count 失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void clear() {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) {
                client.indices().delete(d -> d.index(indexName));
            }
            createIndex();
            log.info("已重建 Elasticsearch 索引: {}", indexName);
        } catch (Exception e) {
            log.error("清空 Elasticsearch 索引失败: {}", e.getMessage(), e);
        }
    }

    private void createIndex() throws Exception {
        client.indices().create(c -> c.index(indexName).withJson(new StringReader(indexDefinition())));
    }

    /** 索引定义：默认分词器可配，ACL 字段建成 keyword 便于过滤。 */
    private String indexDefinition() {
        return "{"
                + "\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0,"
                + "\"analysis\":{\"analyzer\":{\"default\":{\"type\":\"" + analyzer + "\"}}}},"
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
                + "\"metadata\":{\"type\":\"object\",\"enabled\":false}"
                + "}}}";
    }

    private ChunkDoc toDoc(Chunk chunk) {
        AccessControl acl = chunk.getAccessControl();
        return new ChunkDoc(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getDocumentTitle(),
                chunk.getContent(),
                chunk.getIndex(),
                acl == null ? Collections.emptyList() : new ArrayList<>(acl.getDepartments()),
                acl == null ? Collections.emptyList() : new ArrayList<>(acl.getRoles()),
                acl == null ? 0 : acl.getSensitivityLevel(),
                acl != null && acl.isPublicAccess(),
                chunk.getMetadata());
    }

    private Chunk toChunk(ChunkDoc doc) {
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

    /** ES 文档结构。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkDoc {
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
    }
}
