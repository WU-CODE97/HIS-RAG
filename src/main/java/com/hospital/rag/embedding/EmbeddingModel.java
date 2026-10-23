package com.hospital.rag.embedding;

import java.util.List;

/**
 * 文本向量化模型抽象。
 */
public interface EmbeddingModel {

    /**
     * 将单条文本向量化。
     *
     * @param text 文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 批量向量化，默认逐个调用 {@link #embed(String)}，实现类可覆写为批量接口。
     *
     * @param texts 文本列表
     * @return 与输入顺序一致的向量列表
     */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * 向量维度。未知时返回 0。
     */
    default int dimension() {
        return 0;
    }
}
