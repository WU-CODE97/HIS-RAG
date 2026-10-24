package com.hospital.rag.conversation;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话存储测试。
 *
 * <p>重点覆盖三件事：历史确实由服务端按会话维护、跨用户不可读、以及各种淘汰策略不会让消息错位。</p>
 */
class InMemoryConversationStoreTest {

    private RagProperties properties;
    private InMemoryConversationStore store;

    private static final UserContext DOCTOR_A = UserContext.builder()
            .userId("D001").departments(java.util.Set.of("心内科")).clearanceLevel(1).build();
    private static final UserContext DOCTOR_B = UserContext.builder()
            .userId("D002").departments(java.util.Set.of("心内科")).clearanceLevel(1).build();

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        store = new InMemoryConversationStore(properties);
    }

    @Test
    @DisplayName("不存在的会话应返回空历史")
    void shouldReturnEmptyForUnknownSession() {
        assertThat(store.history("conv-unknown", DOCTOR_A)).isEmpty();
    }

    @Test
    @DisplayName("会话 id 为空时应返回空历史")
    void shouldReturnEmptyForBlankSessionId() {
        assertThat(store.history(null, DOCTOR_A)).isEmpty();
        assertThat(store.history("  ", DOCTOR_A)).isEmpty();
    }

    @Test
    @DisplayName("追加一轮后应能按时间正序读回一问一答")
    void shouldAppendAndReadBack() {
        store.append("conv-1", DOCTOR_A, "高血压常用哪些药？", "常用 ACEI、ARB、CCB 等。");
        store.append("conv-1", DOCTOR_A, "它们的禁忌是什么？", "妊娠期禁用 ACEI。");

        List<ChatMessage> history = store.history("conv-1", DOCTOR_A);

        assertThat(history).hasSize(4);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("高血压常用哪些药？");
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(history.get(2).getContent()).isEqualTo("它们的禁忌是什么？");
    }

    @Test
    @DisplayName("答案为空时只记录用户问题，避免历史里出现空助手消息")
    void shouldSkipBlankAnswer() {
        store.append("conv-1", DOCTOR_A, "第一条", null);

        assertThat(store.history("conv-1", DOCTOR_A)).hasSize(1);
    }

    @Test
    @DisplayName("返回的是副本，调用方修改不应影响存储内容")
    void shouldReturnDefensiveCopy() {
        store.append("conv-1", DOCTOR_A, "原问题", "原答案");

        List<ChatMessage> history = store.history("conv-1", DOCTOR_A);
        history.get(0).setContent("被篡改");

        assertThat(store.history("conv-1", DOCTOR_A).get(0).getContent()).isEqualTo("原问题");
    }

    @Test
    @DisplayName("其他用户读同一会话应被拒绝，不能静默返回空")
    void shouldRejectForeignRead() {
        store.append("conv-1", DOCTOR_A, "高血压常用哪些药？", "常用 ACEI。");

        assertThatThrownBy(() -> store.history("conv-1", DOCTOR_B))
                .isInstanceOf(ConversationAccessException.class);
    }

    @Test
    @DisplayName("其他用户写同一会话应被拒绝")
    void shouldRejectForeignAppend() {
        store.append("conv-1", DOCTOR_A, "高血压常用哪些药？", "常用 ACEI。");

        assertThatThrownBy(() -> store.append("conv-1", DOCTOR_B, "越权写入", "越权答案"))
                .isInstanceOf(ConversationAccessException.class);
        assertThat(store.history("conv-1", DOCTOR_A)).hasSize(2);
    }

    @Test
    @DisplayName("其他用户清空会话应被拒绝")
    void shouldRejectForeignClear() {
        store.append("conv-1", DOCTOR_A, "问题", "答案");

        assertThatThrownBy(() -> store.clear("conv-1", DOCTOR_B))
                .isInstanceOf(ConversationAccessException.class);
        assertThat(store.history("conv-1", DOCTOR_A)).hasSize(2);
    }

    @Test
    @DisplayName("超出消息上限时应按轮淘汰最早的对话，且保持 user/assistant 成对")
    void shouldTrimOldestTurns() {
        properties.getConversation().setMaxMessagesPerSession(4);

        store.append("conv-1", DOCTOR_A, "第1问", "第1答");
        store.append("conv-1", DOCTOR_A, "第2问", "第2答");
        store.append("conv-1", DOCTOR_A, "第3问", "第3答");

        List<ChatMessage> history = store.history("conv-1", DOCTOR_A);
        assertThat(history).hasSize(4);
        assertThat(history.get(0).getContent()).isEqualTo("第2问");
        assertThat(history.get(3).getContent()).isEqualTo("第3答");
    }

    @Test
    @DisplayName("会话空闲超过 TTL 后应视为不存在")
    void shouldExpireAfterTtl() throws InterruptedException {
        properties.getConversation().setSessionTtl(Duration.ofMillis(100));
        store.append("conv-1", DOCTOR_A, "问题", "答案");

        assertThat(store.history("conv-1", DOCTOR_A)).hasSize(2);
        Thread.sleep(200);

        assertThat(store.history("conv-1", DOCTOR_A)).isEmpty();
    }

    @Test
    @DisplayName("会话数超过上限时应淘汰最久未访问的会话")
    void shouldEvictLeastRecentlyUsedSession() throws InterruptedException {
        properties.getConversation().setMaxSessions(2);

        store.append("conv-1", DOCTOR_A, "问题1", "答案1");
        Thread.sleep(5);
        store.append("conv-2", DOCTOR_A, "问题2", "答案2");
        Thread.sleep(5);
        store.append("conv-3", DOCTOR_A, "问题3", "答案3");

        assertThat(store.sessionCount()).isEqualTo(2);
        assertThat(store.history("conv-1", DOCTOR_A)).isEmpty();
        assertThat(store.history("conv-2", DOCTOR_A)).hasSize(2);
        assertThat(store.history("conv-3", DOCTOR_A)).hasSize(2);
    }

    @Test
    @DisplayName("清空会话后历史应为空")
    void shouldClearSession() {
        store.append("conv-1", DOCTOR_A, "问题", "答案");

        assertThat(store.clear("conv-1", DOCTOR_A)).isTrue();
        assertThat(store.history("conv-1", DOCTOR_A)).isEmpty();
        assertThat(store.clear("conv-1", DOCTOR_A)).isFalse();
    }

    @Test
    @DisplayName("关闭多轮对话开关后，读写历史都不生效")
    void shouldIgnoreWhenDisabled() {
        properties.getConversation().setEnabled(false);

        store.append("conv-1", DOCTOR_A, "问题", "答案");

        assertThat(store.history("conv-1", DOCTOR_A)).isEmpty();
        assertThat(store.sessionCount()).isZero();
    }
}
