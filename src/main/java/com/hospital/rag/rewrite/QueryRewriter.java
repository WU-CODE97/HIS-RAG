package com.hospital.rag.rewrite;

import com.hospital.rag.model.ChatMessage;

import java.util.List;

/**
 * 多轮对话查询改写抽象。
 *
 * <p>把带指代、省略的追问（如「它的禁忌是什么」「那上面说的第二条」）改写为
 * 不依赖上下文即可独立检索的完整问题。</p>
 */
public interface QueryRewriter {

    /**
     * 改写查询。
     *
     * @param query   用户本轮原始问题
     * @param history 历史对话（时间正序，最后一条为本轮之前的最新消息）
     * @return 改写后的独立问题；无历史或改写失败时原样返回
     */
    String rewrite(String query, List<ChatMessage> history);
}
