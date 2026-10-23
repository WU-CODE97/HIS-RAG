package com.hospital.rag.controller;

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
import com.hospital.rag.service.RagService;
import com.hospital.rag.store.ChunkStore;
import com.hospital.rag.store.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HIS-RAG REST 接口。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final Evaluator evaluator;
    private final ChunkStore chunkStore;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final ObjectProvider<HybridSearchIndex> hybridSearchIndex;
    private final SemanticCache semanticCache;

    public RagController(RagService ragService,
                         Evaluator evaluator,
                         ChunkStore chunkStore,
                         VectorStore vectorStore,
                         Bm25Index bm25Index,
                         ObjectProvider<HybridSearchIndex> hybridSearchIndex,
                         SemanticCache semanticCache) {
        this.ragService = ragService;
        this.evaluator = evaluator;
        this.chunkStore = chunkStore;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.hybridSearchIndex = hybridSearchIndex;
        this.semanticCache = semanticCache;
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
     * <p>请求示例：</p>
     * <pre>
     * {
     *   "query": "它的禁忌是什么？",
     *   "user": { "userId": "D001", "departments": ["心内科"], "roles": ["DOCTOR"], "clearanceLevel": 1 },
     *   "history": [
     *     { "role": "user", "content": "高血压常用哪些药？" },
     *     { "role": "assistant", "content": "常用 ACEI、ARB、CCB 等……" }
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/query")
    public ResponseEntity<RagAnswer> query(@RequestBody QueryRequest request) {
        UserContext user = request.user() == null ? UserContext.guest() : request.user();
        List<ChatMessage> history = request.history() == null ? Collections.emptyList() : request.history();
        return ResponseEntity.ok(ragService.query(request.query(), user, history));
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

    /** 问答 / 检索请求；{@code history} 仅问答使用。 */
    public record QueryRequest(String query, UserContext user, List<ChatMessage> history, Integer topK) {
    }

    /** 评估请求。 */
    public record EvalRequest(List<Evaluator.EvalCase> cases, UserContext user, Integer topK) {
    }
}
