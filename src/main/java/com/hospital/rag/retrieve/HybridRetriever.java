package com.hospital.rag.retrieve;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器：融合文本召回与向量召回。
 *
 * <p>两种运行形态，由容器中是否存在 {@link HybridSearchIndex} 决定：</p>
 * <ul>
 *   <li><b>ES 混合检索</b>（{@code rag.hybrid.backend=elasticsearch}）：
 *       一次 ES 请求内完成 BM25 + kNN 召回与融合，本类只做权限过滤与截断；</li>
 *   <li><b>本地融合</b>（默认）：分别走向量库与 BM25 索引召回，再在 Java 侧融合。
 *       支持 <b>RRF</b>（默认，对排名取倒数加权，无需分数归一化）与
 *       <b>加权归一化</b>（各路 min-max 归一化后按权重求和）两种策略。</li>
 * </ul>
 *
 * <p>标注 {@link Primary}，作为 {@link Retriever} 的默认注入实现。</p>
 */
@Component
@Primary
public class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** 权限过滤前额外扩大的候选倍数，避免过滤后候选不足。 */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final VectorRetriever vectorRetriever;
    private final Bm25Retriever bm25Retriever;
    private final EmbeddingModel embeddingModel;
    private final ObjectProvider<HybridSearchIndex> hybridSearchIndex;
    private final RagProperties.Retrieval config;

    public HybridRetriever(VectorRetriever vectorRetriever,
                           Bm25Retriever bm25Retriever,
                           EmbeddingModel embeddingModel,
                           ObjectProvider<HybridSearchIndex> hybridSearchIndex,
                           RagProperties properties) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.embeddingModel = embeddingModel;
        this.hybridSearchIndex = hybridSearchIndex;
        this.config = properties.getRetrieval();
    }

    @Override
    public List<ScoredChunk> retrieve(String query, UserContext user, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return new ArrayList<>();
        }
        HybridSearchIndex esIndex = hybridSearchIndex.getIfAvailable();
        if (esIndex != null) {
            return retrieveFromHybridIndex(esIndex, query, user, topK);
        }
        return retrieveWithLocalFusion(query, user, topK);
    }

    /**
     * ES 混合检索路径：融合已在 ES 侧完成，这里只负责权限过滤与截断。
     */
    private List<ScoredChunk> retrieveFromHybridIndex(HybridSearchIndex index,
                                                      String query,
                                                      UserContext user,
                                                      int topK) {
        float[] queryVector = embeddingModel.embed(query);
        int candidateSize = Math.max(topK * CANDIDATE_MULTIPLIER, topK + 10);

        List<ScoredChunk> results = new ArrayList<>(Math.min(topK, candidateSize));
        for (ScoredChunk candidate : index.search(query, queryVector, candidateSize)) {
            Chunk chunk = candidate.getChunk();
            if (chunk == null || !chunk.isAccessible(user)) {
                continue;
            }
            results.add(candidate);
            if (results.size() >= topK) {
                break;
            }
        }
        VectorRetriever.assignRank(results);
        log.debug("ES 混合检索完成: backend={}, 候选={}, 通过权限={}", index.name(), candidateSize, results.size());
        return results;
    }

    /**
     * 本地融合路径：两路召回 + Java 侧融合。
     */
    private List<ScoredChunk> retrieveWithLocalFusion(String query, UserContext user, int topK) {
        List<ScoredChunk> vectorHits = vectorRetriever.retrieve(query, user, config.getVectorTopK());
        List<ScoredChunk> bm25Hits = bm25Retriever.retrieve(query, user, config.getBm25TopK());

        List<ScoredChunk> fused = config.isUseRrf()
                ? reciprocalRankFusion(vectorHits, bm25Hits)
                : weightedFusion(vectorHits, bm25Hits);

        if (fused.size() > topK) {
            fused = new ArrayList<>(fused.subList(0, topK));
        }
        VectorRetriever.assignRank(fused);
        return fused;
    }

    /**
     * RRF 融合：score = Σ 1 / (k + rank)。
     */
    private List<ScoredChunk> reciprocalRankFusion(List<ScoredChunk> vectorHits, List<ScoredChunk> bm25Hits) {
        int k = config.getRrfK();
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        accumulateRrf(vectorHits, merged, scores, k, config.getVectorWeight());
        accumulateRrf(bm25Hits, merged, scores, k, config.getBm25Weight());

        List<ScoredChunk> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            ScoredChunk sc = merged.get(entry.getKey());
            sc.setScore(entry.getValue());
            sc.setSource("hybrid");
            result.add(sc);
        }
        result.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
        return result;
    }

    private void accumulateRrf(List<ScoredChunk> hits,
                               Map<String, ScoredChunk> merged,
                               Map<String, Double> scores,
                               int k,
                               double weight) {
        for (int i = 0; i < hits.size(); i++) {
            ScoredChunk hit = hits.get(i);
            String id = hit.getChunk().getId();
            merged.putIfAbsent(id, hit);
            double contribution = weight / (k + (i + 1));
            scores.merge(id, contribution, Double::sum);
        }
    }

    /**
     * 加权归一化融合：各路 min-max 归一化后按权重求和。
     */
    private List<ScoredChunk> weightedFusion(List<ScoredChunk> vectorHits, List<ScoredChunk> bm25Hits) {
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        accumulateWeighted(vectorHits, merged, scores, config.getVectorWeight(), "vectorScore");
        accumulateWeighted(bm25Hits, merged, scores, config.getBm25Weight(), "bm25Score");

        List<ScoredChunk> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            ScoredChunk sc = merged.get(entry.getKey());
            sc.setScore(entry.getValue());
            sc.setSource("hybrid");
            result.add(sc);
        }
        result.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
        return result;
    }

    private void accumulateWeighted(List<ScoredChunk> hits,
                                    Map<String, ScoredChunk> merged,
                                    Map<String, Double> scores,
                                    double weight,
                                    String scoreField) {
        if (hits.isEmpty()) {
            return;
        }
        double min = hits.stream().mapToDouble(this::scoreOf).min().orElse(0.0);
        double max = hits.stream().mapToDouble(this::scoreOf).max().orElse(0.0);
        double range = max - min;

        for (ScoredChunk hit : hits) {
            String id = hit.getChunk().getId();
            merged.putIfAbsent(id, hit);
            double normalized = range == 0 ? 1.0 : (scoreOf(hit) - min) / range;
            scores.merge(id, weight * normalized, Double::sum);
            recordComponent(hit, scoreField);
        }
    }

    private void recordComponent(ScoredChunk hit, String scoreField) {
        // 融合结果保留原始分量分，便于调试
        if ("vectorScore".equals(scoreField) && hit.getVectorScore() == 0.0) {
            hit.setVectorScore(hit.getScore());
        } else if ("bm25Score".equals(scoreField) && hit.getBm25Score() == 0.0) {
            hit.setBm25Score(hit.getScore());
        }
    }

    private double scoreOf(ScoredChunk chunk) {
        return chunk.getScore();
    }

    /** 供测试与调试：判断某片段 id 是否在结果中。 */
    public static boolean contains(List<ScoredChunk> chunks, String chunkId) {
        for (ScoredChunk sc : chunks) {
            Chunk c = sc.getChunk();
            if (c != null && chunkId.equals(c.getId())) {
                return true;
            }
        }
        return false;
    }
}
