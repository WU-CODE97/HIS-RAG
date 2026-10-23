package com.hospital.rag.rewrite;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 LLM 的多轮查询改写器。
 *
 * <p>将最近若干轮对话与本轮问题一并交给模型，输出一个自包含的检索查询。
 * 任何异常、空输出或异常长度都会降级为「原样使用用户问题」，保证主链路可用。</p>
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 改写结果长度上限，超出视为异常输出。 */
    private static final int MAX_REWRITTEN_LENGTH = 300;

    private static final String SYSTEM_PROMPT = """
            你是医院知识库的检索查询改写器。请结合对话历史，把用户最新的问题改写成一句
            「不依赖上下文即可独立理解」的检索查询。
            要求：
            1. 补全代词与省略成分（如「它」「这个」「那上面说的」「第二种」）；
            2. 保留原问题中的关键实体、科室、药名与限定条件；
            3. 不要回答问题，不要解释，只输出改写后的查询本身；
            4. 若最新问题本身已经完整独立，则原样输出；
            5. 若最新问题与历史完全无关，也原样输出。
            """;

    private final LlmClient llmClient;
    private final RagProperties.Conversation config;

    public LlmQueryRewriter(LlmClient llmClient, RagProperties properties) {
        this.llmClient = llmClient;
        this.config = properties.getConversation();
    }

    @Override
    public String rewrite(String query, List<ChatMessage> history) {
        if (query == null || query.isBlank()) {
            return query;
        }
        if (!config.isEnabled() || !config.isRewriteEnabled()) {
            return query;
        }
        if (history == null || history.isEmpty()) {
            return query;
        }
        try {
            String rewritten = llmClient.chat(SYSTEM_PROMPT, buildPrompt(query, history));
            return sanitize(rewritten, query);
        } catch (Exception e) {
            log.warn("查询改写失败，回退为原始问题: {}", e.getMessage());
            return query;
        }
    }

    private String buildPrompt(String query, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("对话历史：\n");

        int maxMessages = Math.max(2, config.getMaxHistoryTurns() * 2);
        int from = Math.max(0, history.size() - maxMessages);
        for (int i = from; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            if (message == null || message.getContent() == null) {
                continue;
            }
            String role = message.isUser() ? "用户" : "助手";
            sb.append(role).append('：').append(message.getContent()).append('\n');
        }

        sb.append("\n用户最新问题：").append(query).append('\n');
        sb.append("\n请只输出改写后的检索查询（不要带任何前缀、引号或说明）。");
        return sb.toString();
    }

    /**
     * 清理模型输出：去掉引号、常见前缀与多余换行；结果不可信时回退原问题。
     */
    private String sanitize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw.strip();

        // 去掉整体包裹的中英文引号
        text = stripWrappingQuotes(text);

        // 去掉模型偶尔带出的前缀
        for (String prefix : new String[]{"改写后：", "改写后的查询：", "改写结果：", "查询：", "问题："}) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).strip();
            }
        }

        // 只取第一行，避免模型输出多行解释
        int newline = text.indexOf('\n');
        if (newline > 0) {
            text = text.substring(0, newline).strip();
        }

        if (text.isEmpty() || text.length() > MAX_REWRITTEN_LENGTH) {
            log.warn("查询改写输出异常（长度={}），回退为原始问题", text.length());
            return fallback;
        }
        if (!text.equals(fallback)) {
            log.info("查询改写: '{}' -> '{}'", fallback, text);
        }
        return text;
    }

    private String stripWrappingQuotes(String text) {
        String result = text;
        String[] pairs = {"\"\"", "''", "“”", "‘’", "《》"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String pair : pairs) {
                if (result.length() >= 2
                        && result.charAt(0) == pair.charAt(0)
                        && result.charAt(result.length() - 1) == pair.charAt(1)) {
                    result = result.substring(1, result.length() - 1).strip();
                    changed = true;
                }
            }
        }
        return result;
    }
}
