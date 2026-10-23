package com.hospital.rag.retrieve;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;

import java.util.Collection;
import java.util.List;

/**
 * BM25 索引抽象。
 *
 * <p>提供两种后端实现，通过 {@code rag.bm25.backend} 切换：</p>
 * <ul>
 *   <li>{@link InMemoryBm25Index}：内存倒排索引，零外部依赖；</li>
 *   <li>{@link ElasticsearchBm25Index}：由 Elasticsearch 承担 BM25 打分。</li>
 * </ul>
 */
public interface Bm25Index {

    /** 索引单个片段。 */
    void index(Chunk chunk);

    /** 批量索引片段。 */
    void indexAll(Collection<Chunk> chunks);

    /**
     * BM25 检索。
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 按 BM25 得分降序的结果
     */
    List<ScoredChunk> search(String query, int topK);

    /** 已索引片段数。 */
    int size();

    /** 清空索引。 */
    void clear();
}
