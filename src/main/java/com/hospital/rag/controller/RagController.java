package com.hospital.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.rag.cache.SemanticCache;
import com.hospital.rag.eval.Evaluator;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.Document;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.retrieve.Bm25Index;
import com.hospital.rag.retrieve.HybridSearchIndex;
import com.hospital.rag.service.QueryStreamListener;
import com.hospital.rag.service.RagService;
import com.hospital.rag.store.ChunkStore;
import com.hospital.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * HIS-RAG REST 接口。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    /** 流式问答的最长静默时间，超过后容器自动结束连接。 */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final RagService ragService;
    private final Evaluator evaluator;
    private final ChunkStore chunkStore;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final ObjectProvider<HybridSearchIndex> hybridSearchIndex;
    private final SemanticCache semanticCache;
    private final ExecutorService streamExecutor;

    public RagController(RagService ragService,
                         Evaluator evaluator,
                         ChunkStore chunkStore,
                         VectorStore vectorStore,
                         Bm25Index bm25Index,
                         ObjectProvider<HybridSearchIndex> hybridSearchIndex,
                         SemanticCache semanticCache,
                         @Qualifier("ragStreamExecutor") ExecutorService streamExecutor) {
        this.ragService = ragService;
        this.evaluator = evaluator;
        this.chunkStore = chunkStore;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.hybridSearchIndex = hybridSearchIndex;
        this.semanticCache = semanticCache;
        this.streamExecutor = streamExecutor;
    }

    /** 文档入库（会自动失效语义缓存）。 */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestRequest request) {
        int chunks = ragService.ingestAll(request.documents());
        Map<String, Object> body = new HashMap<>();
        body.put("documents", request.documents() == null ? 0 : request.documents().size());
        body.put("chunks", chunks);
        return ResponseEntity.ok(body);
    }

    /**
     * 端到端问答，支持多轮。
     *
     * <p>多轮上下文由服务端按 {@code sessionId} 维护，<b>不需要也不接受客户端传 history</b>：
     * 首次调用不传 {@code sessionId}，响应里会返回新建的会话 id，后续带上它即可延续对话。</p>
     *
     * <pre>
     * // 第 1 轮
     * { "query": "高血压常用哪些药？",
     *   "user": { "userId": "D001", "departments": ["心内科"], "roles": ["DOCTOR"], "clearanceLevel": 1 } }
     * → { "sessionId": "conv-8f1c...", "answer": "常用 ACEI、ARB、CCB 等……" }
     *
     * // 第 2 轮：只带 sessionId，上一轮问答由服务端提供
     * { "query": "它的禁忌是什么？", "sessionId": "conv-8f1c...", "user": { "userId": "D001", ... } }
     * → { "rewrittenQuery": "高血压常用药的禁忌症有哪些", ... }
     * </pre>
     */
    @PostMapping("/query")
    public ResponseEntity<RagAnswer> query(@RequestBody QueryRequest request) {
        UserContext user = request.user() == null ? UserContext.guest() : request.user();
        return ResponseEntity.ok(ragService.query(request.query(), user, request.sessionId()));
    }

    /**
     * 流式问答（SSE）。请求体与 {@link #query} 完全一致，响应改为事件流。
     *
     * <p>事件序列：</p>
     * <pre>
     * event: meta   {"sessionId":"conv-…","rewrittenQuery":"…","cacheHit":false,"citations":[…]}
     * event: delta  {"text":"根据资料可知，"}
     * event: delta  {"text":"工伤联网结算共开通 22 个接口……"}
     * event: done   {"latencyMs":2340,"piiMasked":true,"cacheHit":false,"error":false}
     * event: error  {"message":"……"}   // 仅异常时出现，出现后不再有 done
     * </pre>
     *
     * <p>{@code meta} 在重排之后、生成之前发出，因此前端可以先把引用渲染出来，
     * 正文随后逐段到达。EventSource 只支持 GET，所以浏览器端需用 fetch + ReadableStream 消费。</p>
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@RequestBody QueryRequest request) {
        UserContext user = request.user() == null ? UserContext.guest() : request.user();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseSession session = new SseSession(emitter);

        streamExecutor.submit(() -> {
            try {
                ragService.queryStream(request.query(), user, request.sessionId(), session);
            } catch (Exception e) {
                session.fail(e);
            } finally {
                session.close();
            }
        });
        return emitter;
    }

    /** 把 {@link QueryStreamListener} 的事件翻译成 SSE 事件，并处理客户端断开。 */
    private static final class SseSession implements QueryStreamListener {

        /**
         * SSE 专用序列化器：必须关闭缩进。
         *
         * <p>全局 Jackson 配了 {@code indent-output: true}，格式化后的 JSON 含换行，
         * 写进 SSE 会被拆成多条 {@code data:} 行，前端要按规范拼接才能还原；
         * 而且每个正文分片都带缩进空白，白白放大流量。</p>
         */
        private static final ObjectMapper STREAM_JSON = new ObjectMapper();

        private final SseEmitter emitter;
        /** 客户端断开或已结束时置位，后续事件直接丢弃。 */
        private volatile boolean closed;

        private SseSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onStart(String sessionId, String rewrittenQuery,
                            List<RagAnswer.Citation> citations, boolean cacheHit) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("rewrittenQuery", rewrittenQuery);
            data.put("cacheHit", cacheHit);
            data.put("citations", citations);
            send("meta", data);
        }

        @Override
        public void onDelta(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("text", text);
            send("delta", data);
        }

        @Override
        public void onComplete(RagAnswer answer) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", answer.getSessionId());
            data.put("rewrittenQuery", answer.getRewrittenQuery());
            data.put("latencyMs", answer.getLatencyMs());
            data.put("piiMasked", answer.isPiiMasked());
            data.put("cacheHit", answer.isCacheHit());
            data.put("error", answer.isError());
            send("done", data);
        }

        @Override
        public void onError(Throwable error) {
            fail(error);
        }

        void fail(Throwable error) {
            log.warn("流式问答失败: {}", error.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", error.getMessage() == null ? "问答失败" : error.getMessage());
            send("error", data);
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开，忽略
            }
        }

        private void send(String event, Object data) {
            if (closed) {
                return;
            }
            try {
                String payload = data instanceof String text ? text : STREAM_JSON.writeValueAsString(data);
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // 客户端主动断开（刷新 / 关闭页面）：停止推送，不再抛给调用链
                closed = true;
            }
        }
    }

    /**
     * 查看会话历史（调试 / 页面刷新后恢复用）。
     *
     * <p>{@code userId} 仅用于归属校验：会话历史可能含上一轮暴露的权限相关信息，
     * 因此不属于该用户的会话一律拒绝。</p>
     */
    @GetMapping("/conversation/{sessionId}")
    public ResponseEntity<Map<String, Object>> conversation(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "userId", required = false) String userId) {
        List<ChatMessage> messages = ragService.history(sessionId, ownerContext(userId));
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", sessionId);
        body.put("count", messages.size());
        body.put("messages", messages);
        return ResponseEntity.ok(body);
    }

    /** 清空会话历史（用户主动结束对话 / 退出登录时调用）。 */
    @DeleteMapping("/conversation/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearConversation(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "userId", required = false) String userId) {
        boolean cleared = ragService.clearConversation(sessionId, ownerContext(userId));
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", sessionId);
        body.put("cleared", cleared);
        return ResponseEntity.ok(body);
    }

    /**
     * 文件上传入库，支持 PDF / txt / md / csv。
     *
     * <p>curl 示例：</p>
     * <pre>
     * curl -X POST http://localhost:8080/api/rag/ingest/file \
     *   -F "file=@高血压诊疗指南.pdf" \
     *   -F "title=高血压诊疗指南" \
     *   -F "departments=心内科" \
     *   -F "roles=DOCTOR" \
     *   -F "sensitivityLevel=1"
     * </pre>
     */
    @PostMapping(value = "/ingest/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentId", required = false) String documentId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "departments", required = false) List<String> departments,
            @RequestParam(value = "roles", required = false) List<String> roles,
            @RequestParam(value = "sensitivityLevel", required = false, defaultValue = "0") int sensitivityLevel,
            @RequestParam(value = "publicAccess", required = false, defaultValue = "false") boolean publicAccess)
            throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        String id = (documentId == null || documentId.isBlank()) ? "doc-" + UUID.randomUUID() : documentId;
        String docTitle = (title == null || title.isBlank()) ? fileName : title;

        AccessControl accessControl = AccessControl.builder()
                .departments(departments == null ? new HashSet<>() : new HashSet<>(departments))
                .roles(roles == null ? new HashSet<>() : new HashSet<>(roles))
                .sensitivityLevel(sensitivityLevel)
                .publicAccess(publicAccess)
                .build();

        int chunks = ragService.ingestFile(id, docTitle, fileName, file.getBytes(), accessControl);

        Map<String, Object> body = new HashMap<>();
        body.put("documentId", id);
        body.put("fileName", fileName);
        body.put("title", docTitle);
        body.put("chunks", chunks);
        body.put("extracted", chunks > 0);
        return ResponseEntity.ok(body);
    }

    /** 仅检索，便于调试。 */
    @PostMapping("/retrieve")
    public ResponseEntity<List<ScoredChunk>> retrieve(@RequestBody QueryRequest request) {
        UserContext user = request.user() == null ? UserContext.guest() : request.user();
        int topK = request.topK() == null ? 10 : request.topK();
        return ResponseEntity.ok(ragService.retrieveOnly(request.query(), user, topK));
    }

    /** 检索效果评估。 */
    @PostMapping("/eval")
    public ResponseEntity<Evaluator.EvalResult> evaluate(@RequestBody EvalRequest request) {
        UserContext user = request.user() == null ? UserContext.guest() : request.user();
        int k = request.topK() == null ? 5 : request.topK();
        return ResponseEntity.ok(evaluator.evaluate(request.cases(), user, k));
    }

    /** 知识库统计信息。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> body = new HashMap<>();
        body.put("chunks", chunkStore.size());
        body.put("vectors", vectorStore.size());
        body.put("bm25Documents", bm25Index.size());
        body.put("hybrid", hybridStats());
        body.put("cache", semanticCache.stats());
        Map<String, Object> conversation = new HashMap<>();
        conversation.put("sessions", ragService.conversationCount());
        body.put("conversation", conversation);
        return ResponseEntity.ok(body);
    }

    /** 混合检索后端状态：未启用时 {@code enabled=false}，仅用于排查配置。 */
    private Map<String, Object> hybridStats() {
        Map<String, Object> stats = new HashMap<>();
        HybridSearchIndex index = hybridSearchIndex.getIfAvailable();
        stats.put("enabled", index != null);
        if (index != null) {
            stats.put("backend", index.name());
            stats.put("documents", index.size());
        }
        return stats;
    }

    /** 语义缓存统计。 */
    @GetMapping("/cache/stats")
    public ResponseEntity<SemanticCache.CacheStats> cacheStats() {
        return ResponseEntity.ok(semanticCache.stats());
    }

    /** 手动清空语义缓存。 */
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        semanticCache.invalidateAll();
        Map<String, Object> body = new HashMap<>();
        body.put("cleared", true);
        return ResponseEntity.ok(body);
    }

    /** 清空知识库（片段 / 向量 / 索引 / 缓存）。 */
    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, Object>> clear() {
        chunkStore.clear();
        vectorStore.clear();
        bm25Index.clear();
        HybridSearchIndex hybrid = hybridSearchIndex.getIfAvailable();
        if (hybrid != null) {
            hybrid.clear();
        }
        semanticCache.invalidateAll();
        Map<String, Object> body = new HashMap<>();
        body.put("cleared", true);
        return ResponseEntity.ok(body);
    }

    /** 简单健康检查。 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("service", "his-rag");
        return ResponseEntity.ok(body);
    }

    // ---------- 请求体 ----------

    /** 入库请求。 */
    public record IngestRequest(List<Document> documents) {
    }

    /** 问答 / 检索请求；会话上下文由 {@code sessionId} 指定，{@code history} 不再作为入参。 */
    public record QueryRequest(String query, UserContext user, String sessionId, Integer topK) {
    }

    /** 评估请求。 */
    public record EvalRequest(List<Evaluator.EvalCase> cases, UserContext user, Integer topK) {
    }

    /** 仅用于会话归属校验的轻量用户上下文（历史查询接口不涉及检索权限）。 */
    private static UserContext ownerContext(String userId) {
        return UserContext.builder()
                .userId(userId == null || userId.isBlank() ? "anonymous" : userId)
                .build();
    }
}
