package com.hospital.rag.llm;

import java.util.function.Consumer;

/**
 * 大语言模型客户端抽象。
 */
public interface LlmClient {

    /**
     * 发起一次对话补全。
     *
     * @param systemPrompt 系统提示词，可为 null
     * @param userPrompt   用户提示词
     * @return 模型回复文本
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 仅提供用户提示词的便捷方法。
     */
    default String chat(String userPrompt) {
        return chat(null, userPrompt);
    }

    /**
     * 带选项的对话补全。默认实现忽略选项，便于无该能力的实现平滑接入。
     */
    default String chat(String systemPrompt, String userPrompt, ChatOptions options) {
        return chat(systemPrompt, userPrompt);
    }

    /**
     * 流式对话补全：模型每产出一段文本就回调一次 {@code onDelta}。
     *
     * <p>默认实现退化为一次性补齐后整体回调，调用方无需区分上游是否真正流式。</p>
     *
     * @param onDelta 增量文本回调，可能被调用多次
     */
    default void streamChat(String systemPrompt, String userPrompt, ChatOptions options,
                            Consumer<String> onDelta) {
        String text = chat(systemPrompt, userPrompt, options);
        if (text != null && !text.isEmpty()) {
            onDelta.accept(text);
        }
    }
}
