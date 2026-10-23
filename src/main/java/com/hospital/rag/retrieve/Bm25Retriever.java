package com.hospital.rag.retrieve;

import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 检索器：关键词召回，并按用户权限过滤。
 */
@Component
public class Bm25Retriever implements Retriever {

    private static final int CANDIDATE_MULTIPLIER = 4;

    private final Bm25Index bm25Index;

    public Bm25Retriever(Bm25Index bm25Index) {
        this.bm25Index = bm25Index;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, UserContext user, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (query == null || query.isBlank() || topK <= 0) {
            return results;
        }
        int candidateSize = Math.max(topK * CANDIDATE_MULTIPLIER, topK + 10);

        for (ScoredChunk candidate : bm25Index.search(query, candidateSize)) {
            if (candidate.getChunk() != null && candidate.getChunk().isAccessible(user)) {
                results.add(candidate);
                if (results.size() >= topK) {
                    break;
                }
            }
        }
        VectorRetriever.assignRank(results);
        return results;
    }
}
