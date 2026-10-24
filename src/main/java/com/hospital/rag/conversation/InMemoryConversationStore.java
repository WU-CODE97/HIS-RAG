package com.hospital.rag.conversation;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版会话存储。
 *
 * <p>结构：{@code sessionId -> Session}，每个 Session 内持有一个有界的消息列表。
 * 采用 <b>空闲 TTL</b> 与 <b>会话数上限</b> 双重淘汰，避免长期运行后内存无界增长。</p>
 *
 * <p>并发模型：{@link ConcurrentHashMap} 保证会话级隔离，Session 内部用
 * {@code synchronized} 保证同一会话的读写顺序——历史顺序错乱会直接影响改写质量，
 * 因此这里刻意不做无锁优化。</p>
 *
 * <p><b>单机限制</b>：多实例部署时会话各自独立，同一会话无法跨实例延续。
 * 需要横向扩展时替换为 Redis（{@code sessionId -> list}）实现即可，接口不变。</p>
 */
@Component
public class InMemoryConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationStore.class);

    /** 未提供 userId 时的归属标识。 */
    private static final String ANONYMOUS = "anonymous";

    private final RagProperties.Conversation config;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public InMemoryConversationStore(RagProperties properties) {
        this.config = properties.getConversation();
    }

    @Override
    public List<ChatMessage> history(String sessionId, UserContext user) {
        if (!config.isEnabled() || isBlank(sessionId)) {
            return Collections.emptyList();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Collections.emptyList();
        }
        if (session.isExpired(ttlMillis())) {
            sessions.remove(sessionId, session);
            return Collections.emptyList();
        }
        assertOwner(sessionId, session, user);
        session.touch();
        return session.snapshot();
    }

    @Override
    public void append(String sessionId, UserContext user, String question, String answer) {
        if (!config.isEnabled() || isBlank(sessionId) || isBlank(question)) {
            return;
        }
        String owner = ownerOf(user);
        Session session = sessions.computeIfAbsent(sessionId, id -> new Session(owner));
        // sessionId 可能来自请求方，必须校验归属，防止写入他人会话
        assertOwner(sessionId, session, user);
        if (session.isExpired(ttlMillis())) {
            session.reset();
        }
        session.append(question, answer, config.getMaxMessagesPerSession());

        evictExpired();
        evictOverCapacity();
    }

    @Override
    public boolean clear(String sessionId, UserContext user) {
        if (isBlank(sessionId)) {
            return false;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        assertOwner(sessionId, session, user);
        sessions.remove(sessionId, session);
        return true;
    }

    @Override
    public int sessionCount() {
        evictExpired();
        return sessions.size();
    }

    // ---------- 内部实现 ----------

    private void assertOwner(String sessionId, Session session, UserContext user) {
        if (!session.ownerId.equals(ownerOf(user))) {
            log.warn("会话越权访问被拒绝: sessionId={}, owner={}, requester={}",
                    sessionId, session.ownerId, ownerOf(user));
            throw new ConversationAccessException(sessionId);
        }
    }

    private String ownerOf(UserContext user) {
        if (user == null || isBlank(user.getUserId())) {
            return ANONYMOUS;
        }
        return user.getUserId();
    }

    private long ttlMillis() {
        return config.getSessionTtl() == null ? 0L : config.getSessionTtl().toMillis();
    }

    private void evictExpired() {
        long ttl = ttlMillis();
        if (ttl <= 0) {
            return;
        }
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(ttl));
    }

    private void evictOverCapacity() {
        int maxSessions = config.getMaxSessions();
        if (maxSessions <= 0) {
            return;
        }
        while (sessions.size() > maxSessions) {
            Map.Entry<String, Session> oldest = sessions.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessTime()))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            sessions.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 单个会话：有界消息列表 + 最近访问时间。 */
    private static final class Session {

        private final String ownerId;
        private final List<ChatMessage> messages = new ArrayList<>();
        /** 最近访问时间（读或写都会刷新），用于 TTL 与 LRU 淘汰。 */
        private long lastAccess = System.currentTimeMillis();

        private Session(String ownerId) {
            this.ownerId = ownerId;
        }

        private synchronized boolean isExpired(long ttlMillis) {
            return ttlMillis > 0 && System.currentTimeMillis() - lastAccess > ttlMillis;
        }

        private synchronized void touch() {
            lastAccess = System.currentTimeMillis();
        }

        private synchronized void reset() {
            messages.clear();
            lastAccess = System.currentTimeMillis();
        }

        private synchronized long lastAccessTime() {
            return lastAccess;
        }

        private synchronized List<ChatMessage> snapshot() {
            List<ChatMessage> copy = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                copy.add(new ChatMessage(message.getRole(), message.getContent()));
            }
            return copy;
        }

        private synchronized void append(String question, String answer, int maxMessages) {
            messages.add(ChatMessage.user(question));
            if (!isBlank(answer)) {
                messages.add(ChatMessage.assistant(answer));
            }
            // 按「轮」淘汰，保证 user/assistant 始终成对，避免改写时出现孤立消息
            if (maxMessages > 0) {
                while (messages.size() > maxMessages && messages.size() > 2) {
                    messages.remove(0);
                    messages.remove(0);
                }
            }
            lastAccess = System.currentTimeMillis();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
