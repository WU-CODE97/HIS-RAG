package com.hospital.rag.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 LLM 的列表式重排（listwise rerank）。
 *
 * <p>将候选片段编号后交给模型，要求其按相关度输出有序编号数组，再据此重排。
 * 解析失败或调用异常时降级为原始顺序，保证链路可用。</p>
 */
@Component
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private static final String SYSTEM_PROMPT = """
            你是医院知识库的检索结果重排器。请判断每个候选片段与用户问题的相关程度，
            并按相关度从高到低排序。仅输出一个 JSON 整数数组（片段编号），不要输出任何解释或多余文字。
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmReranker(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (candidates.size() <= 1) {
            List<ScoredChunk> single = new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
            VectorRetriever.assignRank(single);
            return single;
        }

        List<Integer> order;
        try {
            String response = llmClient.chat(SYSTEM_PROMPT, buildPrompt(query, candidates));
            order = parseOrder(response, candidates.size());
        } catch (Exception e) {
            log.warn("LLM 重排失败，降级为原始顺序: {}", e.getMessage());
            order = null;
        }

        if (order == null || order.isEmpty()) {
            List<ScoredChunk> fallback = new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
            VectorRetriever.assignRank(fallback);
            return fallback;
        }

        List<ScoredChunk> reranked = new ArrayList<>();
        for (int i = 0; i < order.size() && reranked.size() < topK; i++) {
            int idx = order.get(i);
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            ScoredChunk sc = candidates.get(idx);
            sc.setSource("rerank");
            sc.setScore(1.0 / (i + 1));
            sc.setRank(i + 1);
            reranked.add(sc);
        }
        return reranked;
    }

    private String buildPrompt(String query, List<ScoredChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query).append("\n\n候选片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            String content = candidates.get(i).getChunk().getContent();
            if (content != null && content.length() > 300) {
                content = content.substring(0, 300);
            }
            sb.append('[').append(i).append("] ").append(content).append('\n');
        }
        sb.append("\n请输出排序后的编号 JSON 数组，例如 [3,0,2,1]。");
        return sb.toString();
    }

    /**
     * 解析模型返回的编号数组，截取首个 '[' 到末个 ']' 之间的内容。
     */
    private List<Integer> parseOrder(String response, int size) {
        if (response == null || response.isBlank()) {
            return null;
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = response.substring(start, end + 1);
        try {
            int[] raw = objectMapper.readValue(json, int[].class);
            Set<Integer> seen = new LinkedHashSet<>();
            for (int value : raw) {
                if (value >= 0 && value < size) {
                    seen.add(value);
                }
            }
            // 补齐模型未提及的候选，保证顺序完整
            for (int i = 0; i < size; i++) {
                seen.add(i);
            }
            return new ArrayList<>(seen);
        } catch (Exception e) {
            log.warn("重排结果解析失败: {}", json);
            return null;
        }
    }
}
