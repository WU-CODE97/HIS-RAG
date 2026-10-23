package com.hospital.rag.retrieve;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.util.Tokenizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 BM25 索引（k1 / b 使用经典默认值）。
 *
 * <p>倒排结构为 {@code term -> (chunkId -> tf)}，检索时仅遍历命中词元的倒排表。
 * 仅在 {@code rag.bm25.backend=memory}（默认）时生效。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.bm25.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryBm25Index implements Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final Tokenizer tokenizer;

    /** 倒排索引：term -> (chunkId -> 词频)。 */
    private final Map<String, Map<String, Integer>> invertedIndex = new ConcurrentHashMap<>();
    /** chunkId -> 片段引用。 */
    private final Map<String, Chunk> chunkRefs = new ConcurrentHashMap<>();
    /** chunkId -> 文档长度（词元数）。 */
    private final Map<String, Integer> docLengths = new ConcurrentHashMap<>();

    private volatile double avgDocLength = 0.0;

    public InMemoryBm25Index(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public synchronized void index(Chunk chunk) {
        if (chunk == null || chunk.getId() == null) {
            return;
        }
        List<String> tokens = tokenizer.tokenize(chunk.getContent());
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }
        chunkRefs.put(chunk.getId(), chunk);
        docLengths.put(chunk.getId(), tokens.size());
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            invertedIndex.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                    .put(chunk.getId(), entry.getValue());
        }
        recomputeAvgDocLength();
    }

    @Override
    public synchronized void indexAll(Collection<Chunk> chunks) {
        if (chunks != null) {
            chunks.forEach(this::index);
        }
    }

    @Override
    public synchronized List<ScoredChunk> search(String query, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        Set<String> queryTerms = new LinkedHashSet<>(tokenizer.tokenize(query));
        if (queryTerms.isEmpty() || chunkRefs.isEmpty()) {
            return results;
        }
        int docCount = chunkRefs.size();
        double avg = avgDocLength <= 0 ? 1.0 : avgDocLength;

        Map<String, Double> scores = new HashMap<>();
        for (String term : queryTerms) {
            Map<String, Integer> postings = invertedIndex.get(term);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            int df = postings.size();
            double idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
            for (Map.Entry<String, Integer> posting : postings.entrySet()) {
                String chunkId = posting.getKey();
                int tf = posting.getValue();
                int dl = docLengths.getOrDefault(chunkId, 0);
                double denominator = tf + K1 * (1 - B + B * dl / avg);
                double score = idf * (tf * (K1 + 1)) / denominator;
                scores.merge(chunkId, score, Double::sum);
            }
        }

        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .forEach(e -> results.add(ScoredChunk.builder()
                        .chunk(chunkRefs.get(e.getKey()))
                        .score(e.getValue())
                        .bm25Score(e.getValue())
                        .source("bm25")
                        .build()));
        return results;
    }

    @Override
    public synchronized int size() {
        return chunkRefs.size();
    }

    @Override
    public synchronized void clear() {
        invertedIndex.clear();
        chunkRefs.clear();
        docLengths.clear();
        avgDocLength = 0.0;
    }

    private void recomputeAvgDocLength() {
        if (docLengths.isEmpty()) {
            avgDocLength = 0.0;
            return;
        }
        avgDocLength = docLengths.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
    }
}
