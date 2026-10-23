package com.hospital.rag.llm;

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
 * 基于 OpenAI 兼容接口（{@code POST {baseUrl}/chat/completions}）的 LLM 客户端。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final RagProperties.Llm config;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleLlmClient(RagProperties properties) {
        this.config = properties.getLlm();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String url = trimTrailingSlash(config.getBaseUrl()) + "/chat/completions";

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.setBearerAuth(config.getApiKey());
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ChatResponse response = restTemplate.postForObject(url, request, ChatResponse.class);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("LLM 接口返回为空: url={}", url);
            return "";
        }
        Message message = response.getChoices().get(0).getMessage();
        return message == null || message.getContent() == null ? "" : message.getContent().trim();
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------- OpenAI /chat/completions 响应结构 ----------

    @Data
    @NoArgsConstructor
    public static class ChatResponse {
        private String id;
        private List<Choice> choices;
    }

    @Data
    @NoArgsConstructor
    public static class Choice {
        private int index;
        private Message message;
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }
}
