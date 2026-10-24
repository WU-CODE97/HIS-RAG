package com.hospital.rag.conversation;

/**
 * 会话越权访问：sessionId 存在，但归属用户与请求用户不一致。
 *
 * <p>历史对话可能包含上一轮问答暴露的科室、密级信息，
 * 因此绝不允许跨用户读取，直接拒绝而不是静默返回空。</p>
 */
public class ConversationAccessException extends RuntimeException {

    public ConversationAccessException(String sessionId) {
        super("无权访问会话: " + sessionId);
    }
}
