package com.hospital.rag.retrieve;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;

import java.util.Collection;
import java.util.List;

/**
 * 混合检索索引抽象：把「文本召回 + 向量召回 + 结果融合」收敛为一个后端能力。
 *
 * <p>存在的意义是让两套完全不同的实现可以按配置切换：</p>
 * <ul>
 *   <li><b>本地</b>：不存在本接口的 Bean，{@link HybridRetriever} 走
 *       {@code VectorStore} + {@link Bm25Index} 两路召回，再在 Java 侧做 RRF / 加权融合；</li>
 *   <li><b>Elasticsearch</b>：{@link ElasticsearchHybridIndex} 实现本接口，
 *       文本与向量同存一个索引，由 ES 在协调节点一次性完成召回与融合。</li>
 * </ul>
 *
 * <p>当容器中存在本接口的 Bean 时，{@link HybridRetriever} 优先使用它，
 * 此时 {@code rag.vector.backend} / {@code rag.bm25.backend} 不再参与检索。</p>
 */
public interface HybridSearchIndex {

    /** 后端名称，用于日志与统计展示。 */
    String name();

    /** 批量写入片段及其向量。 */
    void indexAll(Collection<Chunk> chunks, List<float[]> embeddings);

    /**
     * 单次查询完成混合检索。
     *
     * @param queryText   查询文本（走全文 / BM25）
     * @param queryVector 查询向量（走 kNN）
     * @param topK        返回条数
     * @return 已按融合分数降序排列的结果；后端不可用时返回空列表
     */
    List<ScoredChunk> search(String queryText, float[] queryVector, int topK);

    /** 索引内文档总数。 */
    int size();

    /** 清空并重建索引。 */
    void clear();
}
