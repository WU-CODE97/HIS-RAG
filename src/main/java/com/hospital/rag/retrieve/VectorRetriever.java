package com.hospital.rag.retrieve;

import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.store.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索器：语义召回，并按用户权限过滤。
 */
@Component
public class VectorRetriever implements Retriever {

    /** 权限过滤前额外扩大的候选倍数，避免过滤后候选不足。 */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public VectorRetriever(EmbeddingModel embeddingModel, VectorStore vectorStore) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, UserContext user, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (query == null || query.isBlank() || topK <= 0) {
            return results;
        }
        float[] queryVector = embeddingModel.embed(query);
        int candidateSize = Math.max(topK * CANDIDATE_MULTIPLIER, topK + 10);

        for (ScoredChunk candidate : vectorStore.search(queryVector, candidateSize)) {
            if (candidate.getChunk() != null && candidate.getChunk().isAccessible(user)) {
                results.add(candidate);
                if (results.size() >= topK) {
                    break;
                }
            }
        }
        assignRank(results);
        return results;
    }

    static void assignRank(List<ScoredChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setRank(i + 1);
        }
    }
}
