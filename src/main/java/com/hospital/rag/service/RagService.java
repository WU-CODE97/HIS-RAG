package com.hospital.rag.service;

import com.hospital.rag.cache.SemanticCache;
import com.hospital.rag.config.RagProperties;
import com.hospital.rag.generate.AnswerGenerator;
import com.hospital.rag.ingest.DocumentIngestionService;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.ChatMessage;
import com.hospital.rag.model.Document;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.retrieve.Reranker;
import com.hospital.rag.retrieve.Retriever;
import com.hospital.rag.rewrite.QueryRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * RAG 编排服务。
 *
 * <p>主链路：多轮改写 → 语义缓存 → 混合检索 → 重排 → 生成 → 回填缓存。</p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentIngestionService ingestionService;
    private final Retriever retriever;
    private final Reranker reranker;
    private final AnswerGenerator answerGenerator;
    private final QueryRewriter queryRewriter;
    private final SemanticCache semanticCache;
    private final RagProperties.Retrieval retrievalConfig;

    public RagService(DocumentIngestionService ingestionService,
                      Retriever retriever,
                      Reranker reranker,
                      AnswerGenerator answerGenerator,
                      QueryRewriter queryRewriter,
                      SemanticCache semanticCache,
                      RagProperties properties) {
        this.ingestionService = ingestionService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.answerGenerator = answerGenerator;
        this.queryRewriter = queryRewriter;
        this.semanticCache = semanticCache;
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

    /** 单轮问答。 */
    public RagAnswer query(String query, UserContext user) {
        return query(query, user, Collections.emptyList());
    }

    /**
     * 多轮问答。
     *
     * @param query   用户本轮问题
     * @param user    用户上下文（权限过滤 + 缓存权限域）
     * @param history 历史对话（时间正序，不含本轮）
     * @return 带引用、耗时与缓存标记的答案
     */
    public RagAnswer query(String query, UserContext user, List<ChatMessage> history) {
        long start = System.currentTimeMillis();
        UserContext effectiveUser = user == null ? UserContext.guest() : user;

        // 1. 多轮改写：把带指代的追问补全为独立问题
        String effectiveQuery = queryRewriter.rewrite(query, history);

        // 2. 语义缓存：命中则直接返回，跳过检索与生成
        Optional<RagAnswer> cached = semanticCache.get(effectiveQuery, effectiveUser);
        if (cached.isPresent()) {
            RagAnswer hit = cached.get();
            hit.setCacheHit(true);
            hit.setRewrittenQuery(effectiveQuery);
            hit.setLatencyMs(System.currentTimeMillis() - start);
            log.info("问答命中缓存: query='{}', 耗时={}ms", effectiveQuery, hit.getLatencyMs());
            return hit;
        }

        // 3. 混合召回
        int candidateK = Math.max(retrievalConfig.getCandidateTopK(), retrievalConfig.getFinalTopK());
        List<ScoredChunk> candidates = retriever.retrieve(effectiveQuery, effectiveUser, candidateK);

        // 4. 重排
        List<ScoredChunk> contexts;
        if (retrievalConfig.isEnableRerank() && !candidates.isEmpty()) {
            contexts = reranker.rerank(effectiveQuery, candidates, retrievalConfig.getFinalTopK());
        } else {
            contexts = new ArrayList<>(candidates.subList(0, Math.min(retrievalConfig.getFinalTopK(), candidates.size())));
        }

        // 5. 生成（用改写后的问题做检索与生成，但对外保留用户原问题）
        RagAnswer answer = answerGenerator.generate(effectiveQuery, contexts);
        answer.setQuery(query);
        answer.setRewrittenQuery(effectiveQuery);
        answer.setCacheHit(false);
        answer.setLatencyMs(System.currentTimeMillis() - start);

        // 6. 回填缓存（仅成功结果）
        if (!answer.isError()) {
            semanticCache.put(effectiveQuery, effectiveUser, answer);
        }

        log.info("问答完成: query='{}', 改写后='{}', 召回={}, 上下文={}, 耗时={}ms",
                query, effectiveQuery, candidates.size(), contexts.size(), answer.getLatencyMs());
        return answer;
    }

    /** 仅检索不生成，便于调试与评估。 */
    public List<ScoredChunk> retrieveOnly(String query, UserContext user, int topK) {
        UserContext effectiveUser = user == null ? UserContext.guest() : user;
        return retriever.retrieve(query, effectiveUser, topK);
    }
}
