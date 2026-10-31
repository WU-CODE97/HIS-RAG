package com.hospital.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于专用 cross-encoder 接口的重排器（默认 DashScope {@code gte-rerank-v2}）。
 *
 * <p>与 {@link LlmReranker} 的区别：重排模型只做「query-文档相关性打分」这一件事，
 * 不生成任何自然语言，因此没有思维链、没有解码开销，20 条候选约 300ms（实测），
 * 而 LLM 列表式重排在同样候选下即使关闭思维链也需要 1s 以上。</p>
 *
 * <p>成本随候选数线性增长，因此候选数由 {@code rag.rerank.max-candidates} 截断
 * （默认 30，与 {@code retrieval.candidate-top-k} 对齐）。接口超时或异常时直接回退为召回顺序
 * ——重排抖动不应拖垮整个问答。</p>
 */
public class HttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    private final RagProperties.Rerank config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 可选兜底：重排接口不可用且开启 fail-open-to-llm 时使用。 */
    private final Reranker fallback;

    public HttpReranker(RagProperties.Rerank config, Reranker fallback) {
        this.config = config;
        this.fallback = fallback;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(2_000, Math.max(200, config.getTimeoutMs())));
        factory.setReadTimeout(Math.max(200, config.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = Math.min(candidates.size(), Math.max(1, config.getMaxCandidates()));
        List<ScoredChunk> pool = new ArrayList<>(candidates.subList(0, limit));

        if (pool.size() == 1) {
            VectorRetriever.assignRank(pool);
            return pool;
        }

        long start = System.currentTimeMillis();
        try {
            List<ScoredChunk> ranked = score(query, pool, topK);
            if (ranked.isEmpty()) {
                throw new IllegalStateException("重排结果为空");
            }
            log.debug("重排完成: 候选={}, 保留={}, 耗时={}ms", pool.size(), ranked.size(),
                    System.currentTimeMillis() - start);
            return ranked;
        } catch (Exception e) {
            log.warn("重排接口调用失败（耗时={}ms），回退: {}", System.currentTimeMillis() - start, e.getMessage());
            if (fallback != null) {
                return fallback.rerank(query, pool, topK);
            }
            return failOpen(pool, topK);
        }
    }

    /** 调用重排接口并按返回分数排序。 */
    private List<ScoredChunk> score(String query, List<ScoredChunk> pool, int topK) throws Exception {
        List<String> documents = new ArrayList<>(pool.size());
        for (ScoredChunk sc : pool) {
            String content = sc.getChunk() == null ? null : sc.getChunk().getContent();
            documents.add(truncate(content, Math.max(64, config.getMaxDocChars())));
        }

        int topN = Math.min(Math.max(1, topK), pool.size());
        Map<String, Object> body = buildBody(query, documents, topN);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.setBearerAuth(config.getApiKey());
        }

        String raw = restTemplate.postForObject(config.getBaseUrl(),
                new HttpEntity<>(body, headers), String.class);
        return parse(raw, pool, topN);
    }

    private Map<String, Object> buildBody(String query, List<String> documents, int topN) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        if ("cohere".equalsIgnoreCase(config.getApiStyle())) {
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", topN);
        } else {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("return_documents", false);
            parameters.put("top_n", topN);
            body.put("input", input);
            body.put("parameters", parameters);
        }
        return body;
    }

    /**
     * 解析重排结果，兼容 DashScope（{@code output.results}）与 Cohere（{@code results}）两种结构。
     */
    private List<ScoredChunk> parse(String raw, List<ScoredChunk> pool, int topN) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("重排接口返回为空");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray()) {
            results = root.path("results");
        }
        if (!results.isArray()) {
            throw new IllegalStateException("重排响应结构无法识别: " + truncate(raw, 200));
        }

        List<Item> items = new ArrayList<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= pool.size()) {
                continue;
            }
            JsonNode scoreNode = node.has("relevance_score") ? node.get("relevance_score") : node.get("score");
            double score = scoreNode == null ? 0d : scoreNode.asDouble();
            items.add(new Item(index, score));
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("重排响应无有效条目");
        }

        items.sort(Comparator.comparingDouble((Item it) -> it.score).reversed());

        List<ScoredChunk> ranked = new ArrayList<>();
        for (Item item : items) {
            if (ranked.size() >= topN) {
                break;
            }
            ScoredChunk sc = pool.get(item.index);
            sc.setScore(item.score);
            sc.setSource("rerank:" + config.getModel());
            sc.setRank(ranked.size() + 1);
            ranked.add(sc);
        }
        return ranked;
    }

    /** 重排不可用时的降级：保持召回顺序，只做截断。 */
    private List<ScoredChunk> failOpen(List<ScoredChunk> pool, int topK) {
        List<ScoredChunk> result = new ArrayList<>(pool.subList(0, Math.min(topK, pool.size())));
        VectorRetriever.assignRank(result);
        return result;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record Item(int index, double score) {
    }
}
