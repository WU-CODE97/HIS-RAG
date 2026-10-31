package com.hospital.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 基于 OpenAI 兼容接口（{@code POST {baseUrl}/chat/completions}）的 LLM 客户端。
 *
 * <p>支持三种调用形态：</p>
 * <ul>
 *   <li>非流式：{@link #chat(String, String)}</li>
 *   <li>带选项的非流式（可覆盖模型 / 超时 / max-tokens）：{@link #chat(String, String, ChatOptions)}</li>
 *   <li>流式（SSE 增量回调）：{@link #streamChat}，用 JDK HttpClient 逐行读取，不额外引入依赖</li>
 * </ul>
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final RagProperties.Llm config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 按读超时缓存 RestTemplate，避免每次调用都新建连接工厂。 */
    private final Map<Integer, RestTemplate> restTemplates = new ConcurrentHashMap<>();

    /** 流式调用使用 JDK HttpClient（支持按行读取响应体）。 */
    private final HttpClient httpClient;

    public OpenAiCompatibleLlmClient(RagProperties properties) {
        this.config = properties.getLlm();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000, config.getConnectTimeoutMs())))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, ChatOptions.create());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, ChatOptions options) {
        String url = chatUrl();
        ChatOptions opts = options == null ? ChatOptions.create() : options;
        int readTimeout = opts.getTimeoutMs() != null ? opts.getTimeoutMs() : config.getReadTimeoutMs();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.setBearerAuth(config.getApiKey());
        }

        ChatResponse response;
        try {
            response = restTemplate(readTimeout)
                    .postForObject(url, new HttpEntity<>(buildBody(systemPrompt, userPrompt, opts, false), headers),
                            ChatResponse.class);
        } catch (Exception e) {
            // 超时 / 网络异常统一向上抛，由调用方决定降级策略（例如改写回退原问题）
            throw new LlmInvocationException("LLM 调用失败: " + e.getMessage(), e);
        }

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("LLM 接口返回为空: url={}", url);
            return "";
        }
        Message message = response.getChoices().get(0).getMessage();
        return message == null || message.getContent() == null ? "" : message.getContent().trim();
    }

    /**
     * 流式调用：逐行解析 SSE，每拿到一段 {@code delta.content} 就回调一次。
     *
     * <p>注意这里只回调正文，不含 {@code reasoning_content}（思维链）。
     * 若上游开启了思维链，首个正文分片会显著推迟——因此生产配置里应通过
     * {@code rag.llm.extra-body} 关闭它。</p>
     */
    @Override
    public void streamChat(String systemPrompt, String userPrompt, ChatOptions options,
                           Consumer<String> onDelta) {
        String url = chatUrl();
        ChatOptions opts = options == null ? ChatOptions.create() : options;
        int timeout = opts.getTimeoutMs() != null ? opts.getTimeoutMs() : config.getStreamTimeoutMs();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(buildBody(systemPrompt, userPrompt, opts, true));
        } catch (Exception e) {
            throw new LlmInvocationException("流式请求序列化失败: " + e.getMessage(), e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeout))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getApiKey());
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new LlmInvocationException("流式请求失败: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readAll(response.body());
            throw new LlmInvocationException(
                    "流式请求返回 " + response.statusCode() + ": " + truncate(detail, 300), null);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    // 空行（事件分隔）与 ": keep-alive" 注释行直接跳过
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                String delta = extractDelta(data);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } catch (Exception e) {
            throw new LlmInvocationException("流式读取中断: " + e.getMessage(), e);
        }
    }

    /** 从单个 SSE data 负载中取出 {@code choices[0].delta.content}，无正文时返回 null。 */
    private String extractDelta(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null) {
                return null;
            }
            JsonNode content = delta.get("content");
            return content == null || content.isNull() ? null : content.asText();
        } catch (Exception e) {
            // 个别厂商会插入非 JSON 的心跳帧，忽略即可
            log.debug("无法解析的流式分片: {}", truncate(data, 120));
            return null;
        }
    }

    private Map<String, Object> buildBody(String systemPrompt, String userPrompt,
                                          ChatOptions options, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        // 厂商私有参数先铺底，显式配置项再覆盖，避免 extra-body 误改关键字段
        if (config.getExtraBody() != null && !config.getExtraBody().isEmpty()) {
            body.putAll(config.getExtraBody());
        }
        body.put("model", options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : config.getModel());
        body.put("messages", messages);
        body.put("temperature", options.getTemperature() != null
                ? options.getTemperature() : config.getTemperature());
        body.put("max_tokens", options.getMaxTokens() != null
                ? options.getMaxTokens() : config.getMaxTokens());
        if (options.getEnableThinking() != null) {
            body.put("enable_thinking", options.getEnableThinking());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private RestTemplate restTemplate(int readTimeoutMs) {
        return restTemplates.computeIfAbsent(readTimeoutMs, timeout -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Math.max(1_000, config.getConnectTimeoutMs()));
            factory.setReadTimeout(timeout);
            return new RestTemplate(factory);
        });
    }

    private String chatUrl() {
        return trimTrailingSlash(config.getBaseUrl()) + "/chat/completions";
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String readAll(InputStream in) {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 调用上游失败（超时 / 非 2xx / 连接异常）时抛出，调用方据此降级。 */
    public static class LlmInvocationException extends RuntimeException {
        public LlmInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
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
