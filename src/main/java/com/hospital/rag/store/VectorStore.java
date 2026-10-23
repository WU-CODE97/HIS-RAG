package com.hospital.rag.store;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;

import java.util.List;

/**
 * 向量存储抽象。
 */
public interface VectorStore {

    /** 写入单条向量。 */
    void add(Chunk chunk, float[] embedding);

    /** 批量写入。 */
    void addAll(List<Chunk> chunks, List<float[]> embeddings);

    /**
     * 按向量相似度检索。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @return 按相似度降序的结果
     */
    List<ScoredChunk> search(float[] queryVector, int topK);

    /** 已写入向量数。 */
    int size();

    /** 清空。 */
    void clear();
}
