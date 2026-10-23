package com.hospital.rag.rewrite;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮查询改写器测试，重点覆盖各种降级路径（模型异常/输出异常时必须回退原问题）。
 */
class LlmQueryRewriterTest {

    private RagProperties properties;
    private StubLlmClient llmClient;
    private LlmQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        llmClient = new StubLlmClient();
        rewriter = new LlmQueryRewriter(llmClient, properties);
    }

    @Test
    @DisplayName("无历史对话时应原样返回问题，且不调用模型")
    void shouldReturnQueryWhenNoHistory() {
        String result = rewriter.rewrite("高血压用药禁忌", Collections.emptyList());

        assertThat(result).isEqualTo("高血压用药禁忌");
        assertThat(llmClient.callCount).isZero();
    }

    @Test
    @DisplayName("有历史时应把指代型追问改写为独立问题")
    void shouldRewriteFollowUpQuestion() {
        llmClient.response = "高血压常用药的禁忌症有哪些";
        List<ChatMessage> history = List.of(
                ChatMessage.user("高血压常用哪些药？"),
                ChatMessage.assistant("常用 ACEI、ARB、CCB 等。"));

        String result = rewriter.rewrite("它的禁忌是什么？", history);

        assertThat(result).isEqualTo("高血压常用药的禁忌症有哪些");
        assertThat(llmClient.callCount).isEqualTo(1);
        assertThat(llmClient.lastPrompt).contains("高血压常用哪些药").contains("它的禁忌是什么");
    }

    @Test
    @DisplayName("模型返回带引号时应去掉包裹引号")
    void shouldStripWrappingQuotes() {
        llmClient.response = "“高血压常用药的禁忌症有哪些”";

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("高血压常用药的禁忌症有哪些");
    }

    @Test
    @DisplayName("模型输出多行解释时应只取第一行")
    void shouldTakeFirstLineOnly() {
        llmClient.response = "高血压常用药的禁忌症有哪些\n\n说明：这里补全了「它」指代的高血压用药。";

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("高血压常用药的禁忌症有哪些");
    }

    @Test
    @DisplayName("模型调用异常时应回退为原问题")
    void shouldFallbackOnModelFailure() {
        llmClient.failWith = new RuntimeException("connection refused");

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("它的禁忌是什么？");
    }

    @Test
    @DisplayName("模型返回空内容时应回退为原问题")
    void shouldFallbackOnBlankResponse() {
        llmClient.response = "   ";

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("它的禁忌是什么？");
    }

    @Test
    @DisplayName("模型返回超长内容时应回退为原问题")
    void shouldFallbackOnOverlongResponse() {
        llmClient.response = "药".repeat(500);

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("它的禁忌是什么？");
    }

    @Test
    @DisplayName("关闭改写开关时应原样返回问题，且不调用模型")
    void shouldSkipWhenDisabled() {
        properties.getConversation().setRewriteEnabled(false);

        String result = rewriter.rewrite("它的禁忌是什么？", history());

        assertThat(result).isEqualTo("它的禁忌是什么？");
        assertThat(llmClient.callCount).isZero();
    }

    private List<ChatMessage> history() {
        return List.of(
                ChatMessage.user("高血压常用哪些药？"),
                ChatMessage.assistant("常用 ACEI、ARB、CCB 等。"));
    }

    /** 可编程的 LLM 桩。 */
    private static class StubLlmClient implements LlmClient {
        private String response = "";
        private RuntimeException failWith;
        private int callCount;
        private String lastPrompt;

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            callCount++;
            lastPrompt = userPrompt;
            if (failWith != null) {
                throw failWith;
            }
            return response;
        }
    }
}
