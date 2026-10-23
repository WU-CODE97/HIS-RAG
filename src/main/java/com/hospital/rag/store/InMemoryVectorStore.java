package com.hospital.rag.store;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量存储，使用余弦相似度做暴力检索。
 *
 * <p>仅在 {@code rag.vector.backend=memory}（默认）时生效；
 * 生产环境请切换为 {@link PgVectorStore}（pgvector）。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.vector.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void add(Chunk chunk, float[] embedding) {
        if (chunk == null || embedding == null) {
            return;
        }
        entries.add(new Entry(chunk, embedding));
    }

    @Override
    public void addAll(List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks == null || embeddings == null) {
            return;
        }
        int n = Math.min(chunks.size(), embeddings.size());
        for (int i = 0; i < n; i++) {
            add(chunks.get(i), embeddings.get(i));
        }
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (queryVector == null || queryVector.length == 0) {
            return results;
        }
        for (Entry entry : entries) {
            double similarity = cosine(queryVector, entry.embedding);
            results.add(ScoredChunk.builder()
                    .chunk(entry.chunk)
                    .score(similarity)
                    .vectorScore(similarity)
                    .source("vector")
                    .build());
        }
        results.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
        return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    /** 余弦相似度，向量为零向量时返回 0。 */
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 向量条目。 */
    private record Entry(Chunk chunk, float[] embedding) {
    }
}
