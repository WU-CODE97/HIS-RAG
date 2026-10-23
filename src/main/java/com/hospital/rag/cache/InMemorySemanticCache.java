package com.hospital.rag.cache;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版语义缓存。
 *
 * <p>命中判定：查询向量与缓存条目向量的余弦相似度 ≥ {@code rag.cache.similarity-threshold}，
 * 且两者权限域（scope）完全一致。采用 TTL + 容量上限双重淘汰。</p>
 *
 * <p>以线性扫描实现，适合单机中小规模（默认上限 1000 条，1536 维下单次扫描约 1~2ms）。
 * 若需跨实例共享或更大规模，可替换为 Redis + RediSearch 向量检索。</p>
 *
 * <p>说明：一次未命中会多消耗一次 embedding 调用，但省下的是成本高得多的 LLM 生成调用，
 * 整体是划算的。</p>
 */
@Component
public class InMemorySemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(InMemorySemanticCache.class);

    private final EmbeddingModel embeddingModel;
    private final RagProperties.Cache config;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public InMemorySemanticCache(EmbeddingModel embeddingModel, RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.config = properties.getCache();
    }

    @Override
    public Optional<RagAnswer> get(String query, UserContext user) {
        if (!config.isEnabled() || query == null || query.isBlank() || entries.isEmpty()) {
            return Optional.empty();
        }
        float[] queryVector = embed(query);
        if (queryVector.length == 0) {
            return Optional.empty();
        }
        evictExpired();

        String scope = scopeKey(user);
        double threshold = config.getSimilarityThreshold();
        Entry best = null;
        double bestSimilarity = -1.0;

        for (Entry entry : entries) {
            if (!entry.scopeKey.equals(scope)) {
                continue;
            }
            double similarity = cosine(queryVector, entry.embedding);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = entry;
            }
        }

        if (best != null && bestSimilarity >= threshold) {
            best.lastAccess.set(System.currentTimeMillis());
            best.hits.incrementAndGet();
            hits.incrementAndGet();
            log.info("语义缓存命中: 相似度={}, 原始问题='{}'", String.format("%.4f", bestSimilarity), best.answer.getQuery());
            return Optional.of(copyOf(best.answer));
        }

        misses.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void put(String query, UserContext user, RagAnswer answer) {
        if (!config.isEnabled() || query == null || query.isBlank() || answer == null) {
            return;
        }
        if (answer.isError() || answer.getAnswer() == null || answer.getAnswer().isBlank()) {
            return;
        }
        float[] queryVector = embed(query);
        if (queryVector.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.add(new Entry(scopeKey(user), queryVector, copyOf(answer), now, new AtomicLong(now), new AtomicLong()));
        evictIfNeeded();
    }

    @Override
    public void invalidateAll() {
        int size = entries.size();
        entries.clear();
        hits.set(0);
        misses.set(0);
        if (size > 0) {
            log.info("语义缓存已清空，共移除 {} 条", size);
        }
    }

    @Override
    public CacheStats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : (double) h / total;
        return new CacheStats(config.isEnabled(), entries.size(), h, m, rate);
    }

    // ---------- 内部实现 ----------

    private float[] embed(String text) {
        try {
            float[] vector = embeddingModel.embed(text);
            return vector == null ? new float[0] : vector;
        } catch (Exception e) {
            log.warn("语义缓存向量化失败，跳过缓存: {}", e.getMessage());
            return new float[0];
        }
    }

    /** 权限域标识：管理员、匿名、以及「密级 + 科室 + 角色」组合各自独立。 */
    private String scopeKey(UserContext user) {
        if (user == null) {
            return "anon";
        }
        if (user.isAdmin()) {
            return "admin";
        }
        List<String> departments = new ArrayList<>(user.getDepartments());
        List<String> roles = new ArrayList<>(user.getRoles());
        Collections.sort(departments);
        Collections.sort(roles);
        return "lvl" + user.getClearanceLevel()
                + "|d:" + String.join(",", departments)
                + "|r:" + String.join(",", roles);
    }

    private void evictExpired() {
        if (config.getTtl() == null || config.getTtl().isZero() || config.getTtl().isNegative()) {
            return;
        }
        long deadline = System.currentTimeMillis() - config.getTtl().toMillis();
        entries.removeIf(entry -> entry.createdAt < deadline);
    }

    private void evictIfNeeded() {
        int maxSize = config.getMaxSize();
        if (maxSize <= 0) {
            return;
        }
        while (entries.size() > maxSize) {
            Entry oldest = entries.stream()
                    .min(Comparator.comparingLong(entry -> entry.lastAccess.get()))
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            entries.remove(oldest);
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
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

    /** 深拷贝，避免调用方修改污染缓存内容。 */
    private RagAnswer copyOf(RagAnswer src) {
        return RagAnswer.builder()
                .query(src.getQuery())
                .answer(src.getAnswer())
                .citations(src.getCitations() == null ? new ArrayList<>() : new ArrayList<>(src.getCitations()))
                .piiMasked(src.isPiiMasked())
                .rewrittenQuery(src.getRewrittenQuery())
                .cacheHit(src.isCacheHit())
                .error(src.isError())
                .latencyMs(src.getLatencyMs())
                .build();
    }

    /** 缓存条目。 */
    private static final class Entry {
        private final String scopeKey;
        private final float[] embedding;
        private final RagAnswer answer;
        private final long createdAt;
        private final AtomicLong lastAccess;
        private final AtomicLong hits;

        private Entry(String scopeKey, float[] embedding, RagAnswer answer,
                      long createdAt, AtomicLong lastAccess, AtomicLong hits) {
            this.scopeKey = scopeKey;
            this.embedding = embedding;
            this.answer = answer;
            this.createdAt = createdAt;
            this.lastAccess = lastAccess;
            this.hits = hits;
        }
    }
}
