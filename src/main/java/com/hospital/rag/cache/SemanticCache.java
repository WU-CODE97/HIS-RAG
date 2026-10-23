package com.hospital.rag.cache;

import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.UserContext;

import java.util.Optional;

/**
 * 语义缓存抽象。
 *
 * <p>以「查询向量的余弦相似度」而非字符串精确匹配来命中缓存，
 * 使「高血压的用药禁忌是什么」与「高血压患者用药有哪些禁忌」这类同义问法可以复用同一份答案。</p>
 *
 * <p><b>安全约束</b>：命中范围严格限定在同一权限域（scope）内。
 * 管理员与普通医生、不同科室的用户各自独立成域，避免高权限答案泄漏给低权限用户。</p>
 */
public interface SemanticCache {

    /**
     * 查询缓存。
     *
     * @param query 已改写后的查询文本
     * @param user  当前用户上下文（决定权限域）
     * @return 命中则返回答案副本，否则为空
     */
    Optional<RagAnswer> get(String query, UserContext user);

    /**
     * 写入缓存（仅成功生成的答案会被写入）。
     */
    void put(String query, UserContext user, RagAnswer answer);

    /** 清空全部缓存（文档变更后必须调用）。 */
    void invalidateAll();

    /** 运行统计。 */
    CacheStats stats();

    /** 缓存统计信息。 */
    record CacheStats(boolean enabled, int size, long hits, long misses, double hitRate) {
    }
}
