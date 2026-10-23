package com.hospital.rag.embedding;

import com.hospital.rag.config.RagProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenAI 兼容接口（{@code POST {baseUrl}/embeddings}）的向量化实现。
 *
 * <p>兼容 OpenAI、Azure OpenAI、通义千问、智谱、DeepSeek、本地 vLLM / Ollama 等
 * 提供同构 {@code /v1/embeddings} 协议的服务。</p>
 */
@Component
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

    private final RagProperties.Embedding config;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleEmbeddingModel(RagProperties properties) {
        this.config = properties.getEmbedding();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return vectors;
        }
        int batchSize = Math.max(1, config.getBatchSize());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            vectors.addAll(callEmbeddingApi(batch));
        }
        return vectors;
    }

    private List<float[]> callEmbeddingApi(List<String> batch) {
        String url = trimTrailingSlash(config.getBaseUrl()) + "/embeddings";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("input", batch);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.setBearerAuth(config.getApiKey());
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        EmbeddingResponse response = restTemplate.postForObject(url, request, EmbeddingResponse.class);

        List<float[]> vectors = new ArrayList<>();
        if (response == null || response.getData() == null) {
            log.warn("Embedding 接口返回为空: url={}", url);
            return vectors;
        }
        for (EmbeddingData item : response.getData()) {
            vectors.add(toFloatArray(item.getEmbedding()));
        }
        return vectors;
    }

    private float[] toFloatArray(List<Double> values) {
        if (values == null) {
            return new float[0];
        }
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).floatValue();
        }
        return arr;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public int dimension() {
        return config.getDimension();
    }

    // ---------- OpenAI /embeddings 响应结构 ----------

    @Data
    @NoArgsConstructor
    public static class EmbeddingResponse {
        private String object;
        private List<EmbeddingData> data;
        private String model;
    }

    @Data
    @NoArgsConstructor
    public static class EmbeddingData {
        private String object;
        private int index;
        private List<Double> embedding;
    }
}
