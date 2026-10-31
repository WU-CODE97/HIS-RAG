package com.hospital.rag.service;

import com.hospital.rag.cache.SemanticCache;
import com.hospital.rag.config.RagProperties;
import com.hospital.rag.conversation.ConversationStore;
import com.hospital.rag.generate.AnswerGenerator;
import com.hospital.rag.ingest.DocumentIngestionService;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.Document;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.retrieve.Reranker;
import com.hospital.rag.retrieve.NeighborExpander;
import com.hospital.rag.retrieve.Retriever;
import com.hospital.rag.rewrite.QueryRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RAG 编排服务。
 *
 * <p>主链路：会话历史 → 多轮改写 → 语义缓存 → 混合检索 → 重排 → 生成 → 回填缓存 → 回写历史。</p>
 *
 * <p><b>多轮对话的上下文由服务端会话持有</b>：调用方只传 {@code sessionId}，
 * 历史不进入请求体，既避免客户端伪造上下文，也避免每轮重复传输整段对话。</p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 新建会话 id 的前缀，便于日志与排查时一眼识别。 */
    private static final String SESSION_PREFIX = "conv-";

    private final DocumentIngestionService ingestionService;
    private final Retriever retriever;
    private final Reranker reranker;
    private final AnswerGenerator answerGenerator;
    private final QueryRewriter queryRewriter;
    private final SemanticCache semanticCache;
    private final ConversationStore conversationStore;
    private final NeighborExpander neighborExpander;
    private final RagProperties.Retrieval retrievalConfig;

    public RagService(DocumentIngestionService ingestionService,
                      Retriever retriever,
                      Reranker reranker,
                      AnswerGenerator answerGenerator,
                      QueryRewriter queryRewriter,
                      SemanticCache semanticCache,
                      ConversationStore conversationStore,
                      NeighborExpander neighborExpander,
                      RagProperties properties) {
        this.ingestionService = ingestionService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.answerGenerator = answerGenerator;
        this.queryRewriter = queryRewriter;
        this.semanticCache = semanticCache;
        this.conversationStore = conversationStore;
        this.neighborExpander = neighborExpander;
        this.retrievalConfig = properties.getRetrieval();
    }

    /** 入库单个文档，返回片段数；同时失效语义缓存。 */
    public int ingest(Document document) {
        int chunks = ingestionService.ingest(document);
        if (chunks > 0) {
            semanticCache.invalidateAll();
        }
        return chunks;
    }

    /** 批量入库，返回累计片段数；同时失效语义缓存。 */
    public int ingestAll(List<Document> documents) {
        int chunks = ingestionService.ingestAll(documents);
        if (chunks > 0) {
            semanticCache.invalidateAll();
        }
        return chunks;
    }

    /**
     * 从文件（PDF / 纯文本）入库，返回片段数；同时失效语义缓存。
     *
     * @param documentId    文档 id
     * @param title         标题
     * @param fileName      原始文件名，用于选择解析器
     * @param content       文件字节
     * @param accessControl 访问控制策略
     */
    public int ingestFile(String documentId, String title, String fileName,
                          byte[] content, AccessControl accessControl) {
        int chunks = ingestionService.ingestFile(documentId, title, fileName, content, accessControl);
        if (chunks > 0) {
            semanticCache.invalidateAll();
        }
        return chunks;
    }

    /** 单轮问答（不延续上下文，服务端为其分配一个一次性会话）。 */
    public RagAnswer query(String query, UserContext user) {
        return query(query, user, null);
    }

    /**
     * 多轮问答。历史从服务端会话中取，<b>不接受调用方传入</b>。
     *
     * @param query     用户本轮问题
     * @param user      用户上下文（权限过滤 + 缓存权限域 + 会话归属校验）
     * @param sessionId 会话 id；为空时创建新会话
     * @return 带引用、耗时、缓存标记与会话 id 的答案
     * @throws com.hospital.rag.conversation.ConversationAccessException 会话不属于当前用户
     */
    public RagAnswer query(String query, UserContext user, String sessionId) {
        long start = System.currentTimeMillis();
        UserContext effectiveUser = user == null ? UserContext.guest() : user;
        String session = (sessionId == null || sessionId.isBlank()) ? newSessionId() : sessionId;

        // 1. 取历史：由会话存储按 sessionId 提供，越权访问会直接抛异常
        List<ChatMessage> history = conversationStore.history(session, effectiveUser);

        // 2. 多轮改写：把带指代的追问补全为独立问题
        String effectiveQuery = queryRewriter.rewrite(query, history);

        // 3. 语义缓存：命中则直接返回，跳过检索与生成
        Optional<RagAnswer> cached = semanticCache.get(effectiveQuery, effectiveUser);
        if (cached.isPresent()) {
            RagAnswer hit = cached.get();
            hit.setSessionId(session);
            hit.setCacheHit(true);
            hit.setRewrittenQuery(effectiveQuery);
            hit.setLatencyMs(System.currentTimeMillis() - start);
            // 缓存命中同样要落历史，否则下一轮改写会丢失这一轮上下文
            recordTurn(session, effectiveUser, query, hit);
            log.info("问答命中缓存: session={}, query='{}', 耗时={}ms", session, effectiveQuery, hit.getLatencyMs());
            return hit;
        }

        // 4 & 5. 混合召回 + 重排
        List<ScoredChunk> candidates = retriever.retrieve(effectiveQuery, effectiveUser, candidateK());
        List<ScoredChunk> contexts = rerank(effectiveQuery, candidates, effectiveUser);

        // 6. 生成（用改写后的问题做检索与生成，但对外保留用户原问题）
        RagAnswer answer = answerGenerator.generate(effectiveQuery, contexts);
        answer.setSessionId(session);
        answer.setQuery(query);
        answer.setRewrittenQuery(effectiveQuery);
        answer.setCacheHit(false);
        answer.setLatencyMs(System.currentTimeMillis() - start);

        // 7. 回填缓存（仅成功结果）
        if (!answer.isError()) {
            semanticCache.put(effectiveQuery, effectiveUser, answer);
        }

        // 8. 回写历史（失败的轮次不记录，避免把错误提示带入下一轮改写）
        recordTurn(session, effectiveUser, query, answer);

        log.info("问答完成: session={}, query='{}', 改写后='{}', 历史={}, 召回={}, 上下文={}, 耗时={}ms",
                session, query, effectiveQuery, history.size(), candidates.size(), contexts.size(), answer.getLatencyMs());
        return answer;
    }

    /**
     * 流式问答：与 {@link #query(String, UserContext, String)} 同一条链路，但正文边生成边下发。
     *
     * <p>事件顺序为 {@code onStart}（引用已就绪）→ {@code onDelta} × N → {@code onComplete}。
     * 语义缓存命中时正文会一次性回调，前端无需区分。</p>
     *
     * @param listener 事件回调，由传输层（SSE）实现
     * @return 完整答案（用于缓存回填与会话历史，调用方可忽略）
     */
    public RagAnswer queryStream(String query, UserContext user, String sessionId,
                                 QueryStreamListener listener) {
        long start = System.currentTimeMillis();
        UserContext effectiveUser = user == null ? UserContext.guest() : user;
        String session = (sessionId == null || sessionId.isBlank()) ? newSessionId() : sessionId;

        // 1. 取历史 → 2. 多轮改写
        List<ChatMessage> history = conversationStore.history(session, effectiveUser);
        String effectiveQuery = queryRewriter.rewrite(query, history);

        // 3. 语义缓存：命中则正文一次性下发，省掉检索与生成
        Optional<RagAnswer> cached = semanticCache.get(effectiveQuery, effectiveUser);
        if (cached.isPresent()) {
            RagAnswer hit = cached.get();
            hit.setSessionId(session);
            hit.setCacheHit(true);
            hit.setRewrittenQuery(effectiveQuery);
            hit.setLatencyMs(System.currentTimeMillis() - start);
            listener.onStart(session, effectiveQuery, hit.getCitations(), true);
            if (hit.getAnswer() != null && !hit.getAnswer().isEmpty()) {
                listener.onDelta(hit.getAnswer());
            }
            recordTurn(session, effectiveUser, query, hit);
            listener.onComplete(hit);
            log.info("流式问答命中缓存: session={}, query='{}', 耗时={}ms", session, effectiveQuery, hit.getLatencyMs());
            return hit;
        }

        // 4 & 5. 召回 + 重排（先把引用推给前端，避免等待正文时页面空白）
        List<ScoredChunk> candidates = retriever.retrieve(effectiveQuery, effectiveUser, candidateK());
        List<ScoredChunk> contexts = rerank(effectiveQuery, candidates, effectiveUser);
        listener.onStart(session, effectiveQuery, answerGenerator.citations(contexts), false);

        // 6. 流式生成
        RagAnswer answer = answerGenerator.generateStreaming(effectiveQuery, contexts, listener::onDelta);
        answer.setSessionId(session);
        answer.setQuery(query);
        answer.setRewrittenQuery(effectiveQuery);
        answer.setCacheHit(false);
        answer.setLatencyMs(System.currentTimeMillis() - start);

        // 7. 回填缓存（仅成功结果）
        if (!answer.isError()) {
            semanticCache.put(effectiveQuery, effectiveUser, answer);
        }

        // 8. 回写历史
        recordTurn(session, effectiveUser, query, answer);

        listener.onComplete(answer);
        log.info("流式问答完成: session={}, query='{}', 改写后='{}', 召回={}, 上下文={}, 耗时={}ms",
                session, query, effectiveQuery, candidates.size(), contexts.size(), answer.getLatencyMs());
        return answer;
    }

    /** 送入重排 / 生成前的召回条数。 */
    private int candidateK() {
        return Math.max(retrievalConfig.getCandidateTopK(), retrievalConfig.getFinalTopK());
    }

    /** 重排；关闭重排时直接截断召回结果。重排结果只作为「锚点」，随后补齐相邻片段。 */
    private List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, UserContext user) {
        List<ScoredChunk> anchors;
        if (retrievalConfig.isEnableRerank() && !candidates.isEmpty()) {
            anchors = reranker.rerank(query, candidates, retrievalConfig.getFinalTopK());
        } else {
            anchors = new ArrayList<>(candidates.subList(0, Math.min(retrievalConfig.getFinalTopK(), candidates.size())));
        }
        return neighborExpander.expand(anchors, user);
    }

    /** 读取会话历史（供调试接口使用，同样受归属校验约束）。 */    public List<ChatMessage> history(String sessionId, UserContext user) {
        return conversationStore.history(sessionId, user);
    }

    /** 清空会话历史。 */
    public boolean clearConversation(String sessionId, UserContext user) {
        return conversationStore.clear(sessionId, user);
    }

    /** 当前存活会话数。 */
    public int conversationCount() {
        return conversationStore.sessionCount();
    }

    /** 仅检索不生成，便于调试与评估。 */
    public List<ScoredChunk> retrieveOnly(String query, UserContext user, int topK) {
        UserContext effectiveUser = user == null ? UserContext.guest() : user;
        return retriever.retrieve(query, effectiveUser, topK);
    }

    /** 生成一个新的会话 id。 */
    private String newSessionId() {
        return SESSION_PREFIX + UUID.randomUUID();
    }

    /**
     * 记录一轮问答到会话。
     *
     * <p>历史上写失败不应影响本次回答，因此只记日志、不向上抛。
     * 生成失败的轮次同样跳过——把错误提示写进历史会污染下一轮的改写。</p>
     */
    private void recordTurn(String sessionId, UserContext user, String query, RagAnswer answer) {
        if (answer == null || answer.isError()) {
            return;
        }
        try {
            conversationStore.append(sessionId, user, query, answer.getAnswer());
        } catch (Exception e) {
            log.warn("会话历史写入失败: session={}, 原因={}", sessionId, e.getMessage());
        }
    }
}
