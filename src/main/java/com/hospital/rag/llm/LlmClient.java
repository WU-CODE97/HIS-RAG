package com.hospital.rag.llm;

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
}
