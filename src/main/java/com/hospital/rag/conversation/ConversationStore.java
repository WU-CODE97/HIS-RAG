package com.hospital.rag.conversation;

import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.UserContext;

import java.util.List;

/**
 * 多轮会话存储抽象。
 *
 * <p><b>设计原则：历史由服务端持有，不接受调用方传入。</b>
 * 调用方只需携带 {@code sessionId}，历史由本存储按会话维护。
 * 这样可以避免客户端伪造 / 篡改历史（提示注入、越权上下文），
 * 也避免每次请求都要回传整段对话。</p>
 *
 * <p><b>安全约束</b>：会话与创建者绑定。会话 id 只作为「定位」用，
 * 真正的访问控制靠 {@code ownerId} 校验——他人即便拿到 sessionId 也无法读取历史。</p>
 */
public interface ConversationStore {

    /**
     * 读取会话历史（时间正序）。
     *
     * @param sessionId 会话 id，为空时返回空列表
     * @param user      当前用户，用于归属校验
     * @return 历史消息副本；会话不存在、已过期或未启用时返回空列表
     * @throws ConversationAccessException 会话存在但不属于该用户
     */
    List<ChatMessage> history(String sessionId, UserContext user);

    /**
     * 追加一轮问答。会话不存在时按当前用户自动创建。
     *
     * @param sessionId 会话 id
     * @param user      当前用户
     * @param question  用户本轮原始问题
     * @param answer    助手答案（为空时只记录用户问题）
     * @throws ConversationAccessException 会话存在但不属于该用户
     */
    void append(String sessionId, UserContext user, String question, String answer);

    /**
     * 清空指定会话。
     *
     * @return 是否确实移除了一个会话
     * @throws ConversationAccessException 会话存在但不属于该用户
     */
    boolean clear(String sessionId, UserContext user);

    /** 当前存活会话数。 */
    int sessionCount();
}
