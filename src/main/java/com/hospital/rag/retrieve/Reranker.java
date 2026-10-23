package com.hospital.rag.retrieve;

import com.hospital.rag.model.ScoredChunk;

import java.util.List;

/**
 * 重排器抽象：对召回候选做精细化排序。
 */
public interface Reranker {

    /**
     * 重排候选片段。
     *
     * @param query      查询文本
     * @param candidates 候选结果
     * @param topK       保留条数
     * @return 重排后的结果
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);
}
