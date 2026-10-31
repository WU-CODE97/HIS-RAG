package com.hospital.rag.retrieve;

import com.hospital.rag.model.ScoredChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 空重排：不做任何调整，只按融合顺序截断。
 *
 * <p>对应 {@code rag.rerank.provider=none}。保留它可以让「关掉重排」成为一次纯配置变更，
 * 而不是要求调用方到处判空。</p>
 */
public class PassThroughReranker implements Reranker {

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<ScoredChunk> result = new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
        VectorRetriever.assignRank(result);
        return result;
    }
}
