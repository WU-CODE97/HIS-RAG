package com.hospital.rag.service;

import com.hospital.rag.cache.SemanticCache;
import com.hospital.rag.config.RagProperties;
import com.hospital.rag.conversation.ConversationAccessException;
import com.hospital.rag.conversation.ConversationStore;
import com.hospital.rag.conversation.InMemoryConversationStore;
import com.hospital.rag.generate.AnswerGenerator;
import com.hospital.rag.ingest.DocumentIngestionService;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.retrieve.Reranker;
import com.hospital.rag.rewrite.QueryRewriter;
import com.hospital.rag.security.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 多轮问答链路测试，聚焦「历史由服务端会话提供」这一行为。
 */
class RagServiceConversationTest {

    private RagProperties properties;
    private ConversationStore store;
    private StubQueryRewriter rewriter;
    private StubSemanticCache cache;
    private StubLlmClient llmClient;
    private RagService service;

    private static final UserContext DOCTOR_A = UserContext.builder()
            .userId("D001").departments(java.util.Set.of("心内科")).roles(java.util.Set.of("DOCTOR"))
            .clearanceLevel(1).build();
    private static final UserContext DOCTOR_B = UserContext.builder()
            .userId("D002").departments(java.util.Set.of("心内科")).roles(java.util.Set.of("DOCTOR"))
            .clearanceLevel(1).build();

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getRetrieval().setEnableRerank(false);

        store = new InMemoryConversationStore(properties);
        rewriter = new StubQueryRewriter();
        cache = new StubSemanticCache();
        llmClient = new StubLlmClient();
        AnswerGenerator generator = new AnswerGenerator(llmClient, new PiiMasker(), properties);

        service = new RagService(
                mock(DocumentIngestionService.class),
                (query, user, topK) -> Collections.emptyList(),
                mock(Reranker.class),
                generator,
                rewriter,
                cache,
                store,
                properties);
    }

    @Test
    @DisplayName("首次提问应自动分配会话 id")
    void shouldAssignSessionId() {
        RagAnswer answer = service.query("高血压常用哪些药？", DOCTOR_A, null);

        assertThat(answer.getSessionId()).startsWith("conv-");
        assertThat(answer.getAnswer()).isEqualTo("常用 ACEI、ARB、CCB 等。");
        assertThat(answer.isError()).isFalse();
    }

    @Test
    @DisplayName("第二轮应能拿到由服务端保存的第一轮历史，而不是由调用方传入")
    void shouldUseServerSideHistory() {
        RagAnswer first = service.query("高血压常用哪些药？", DOCTOR_A, null);

        service.query("它的禁忌是什么？", DOCTOR_A, first.getSessionId());

        List<ChatMessage> history = rewriter.lastHistory;
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getContent()).isEqualTo("高血压常用哪些药？");
        assertThat(history.get(1).getContent()).isEqualTo("常用 ACEI、ARB、CCB 等。");
    }

    @Test
    @DisplayName("同一用户的不同会话之间历史应互相隔离")
    void shouldIsolateSessions() {
        RagAnswer a = service.query("会话 A 的问题", DOCTOR_A, null);
        RagAnswer b = service.query("会话 B 的问题", DOCTOR_A, null);

        assertThat(a.getSessionId()).isNotEqualTo(b.getSessionId());
        service.query("追问", DOCTOR_A, a.getSessionId());

        assertThat(rewriter.lastHistory)
                .extracting(ChatMessage::getContent)
                .containsExactly("会话 A 的问题", "常用 ACEI、ARB、CCB 等。");
    }

    @Test
    @DisplayName("他人携带同一 sessionId 提问应被拒绝，避免读到别人的上下文")
    void shouldRejectForeignSession() {
        RagAnswer first = service.query("高血压常用哪些药？", DOCTOR_A, null);

        assertThatThrownBy(() -> service.query("它的禁忌是什么？", DOCTOR_B, first.getSessionId()))
                .isInstanceOf(ConversationAccessException.class);
    }

    @Test
    @DisplayName("命中语义缓存时也应把这一轮写入历史")
    void shouldRecordTurnOnCacheHit() {
        RagAnswer cached = RagAnswer.builder().answer("缓存答案").build();
        cache.hit = Optional.of(cached);

        RagAnswer answer = service.query("高血压常用哪些药？", DOCTOR_A, "conv-cache");

        assertThat(answer.isCacheHit()).isTrue();
        assertThat(answer.getSessionId()).isEqualTo("conv-cache");
        assertThat(service.history("conv-cache", DOCTOR_A))
                .extracting(ChatMessage::getContent)
                .containsExactly("高血压常用哪些药？", "缓存答案");
    }

    @Test
    @DisplayName("生成失败的轮次不应写入历史")
    void shouldNotRecordFailedTurn() {
        llmClient.failWith = new RuntimeException("upstream timeout");

        RagAnswer answer = service.query("会失败的问题", DOCTOR_A, "conv-fail");

        assertThat(answer.isError()).isTrue();
        assertThat(service.history("conv-fail", DOCTOR_A)).isEmpty();
    }

    @Test
    @DisplayName("关闭多轮对话后应退化为单轮：不读历史也不写历史")
    void shouldDegradeToSingleTurnWhenDisabled() {
        properties.getConversation().setEnabled(false);

        RagAnswer first = service.query("高血压常用哪些药？", DOCTOR_A, null);
        service.query("它的禁忌是什么？", DOCTOR_A, first.getSessionId());

        assertThat(rewriter.lastHistory).isEmpty();
        assertThat(service.history(first.getSessionId(), DOCTOR_A)).isEmpty();
    }

    /** 记录改写时收到的历史。 */
    private static class StubQueryRewriter implements QueryRewriter {
        private List<ChatMessage> lastHistory = new ArrayList<>();

        @Override
        public String rewrite(String query, List<ChatMessage> history) {
            this.lastHistory = history == null ? new ArrayList<>() : new ArrayList<>(history);
            return query;
        }
    }

    /** 可编程的语义缓存桩。 */
    private static class StubSemanticCache implements SemanticCache {
        private Optional<RagAnswer> hit = Optional.empty();
        private RagAnswer stored;

        @Override
        public Optional<RagAnswer> get(String query, UserContext user) {
            return hit;
        }

        @Override
        public void put(String query, UserContext user, RagAnswer answer) {
            stored = answer;
        }

        @Override
        public void invalidateAll() {
        }

        @Override
        public CacheStats stats() {
            return new CacheStats(true, 0, 0, 0, 0.0);
        }
    }

    /** 可编程的 LLM 桩。 */
    private static class StubLlmClient implements LlmClient {
        private String response = "常用 ACEI、ARB、CCB 等。";
        private RuntimeException failWith;

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (failWith != null) {
                throw failWith;
            }
            return response;
        }
    }
}
