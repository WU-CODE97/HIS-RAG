package com.hospital.rag.retrieve;

import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;

import java.util.List;

/**
 * 检索器抽象。所有实现都必须遵守用户上下文中的权限约束。
 */
public interface Retriever {

    /**
     * 执行检索。
     *
     * @param query 查询文本
     * @param user  当前用户上下文（用于权限过滤），可为 null
     * @param topK  返回条数
     * @return 按相关度降序的结果
     */
    List<ScoredChunk> retrieve(String query, UserContext user, int topK);
}
