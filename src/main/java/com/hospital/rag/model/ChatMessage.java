package com.hospital.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮对话消息，用于多轮问答的上下文改写。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 角色：{@code user} / {@code assistant} / {@code system}。 */
    private String role;

    /** 消息内容。 */
    private String content;

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** 判断是否用户消息；加 {@code @JsonIgnore} 避免被序列化成多余的 {@code user} 字段。 */
    @JsonIgnore
    public boolean isUser() {
        return "user".equalsIgnoreCase(role);
    }
}
