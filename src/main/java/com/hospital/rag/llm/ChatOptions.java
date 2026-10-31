package com.hospital.rag.llm;

/**
 * 单次对话调用的可选项。
 *
 * <p>为 {@code null} 的字段一律回退到 {@code rag.llm} 的全局配置，
 * 因此调用方只需要按需覆盖关心的项（例如改写环节覆盖超时与 maxTokens）。</p>
 */
public final class ChatOptions {

    private String model;
    private Integer maxTokens;
    private Double temperature;
    private Integer timeoutMs;
    private Boolean enableThinking;

    private ChatOptions() {
    }

    /** 全部沿用全局配置。 */
    public static ChatOptions create() {
        return new ChatOptions();
    }

    /** 指定超时（毫秒），超时由底层 HTTP 读超时触发，调用方捕获异常后降级。 */
    public static ChatOptions withTimeout(int timeoutMs) {
        return create().model(null).timeoutMs(timeoutMs);
    }

    public ChatOptions model(String value) {
        this.model = value;
        return this;
    }

    public ChatOptions maxTokens(Integer value) {
        this.maxTokens = value;
        return this;
    }

    public ChatOptions temperature(Double value) {
        this.temperature = value;
        return this;
    }

    public ChatOptions timeoutMs(Integer value) {
        this.timeoutMs = value;
        return this;
    }

    /** 覆盖是否开启思维链；{@code null} 表示沿用 {@code rag.llm.extra-body} 的配置。 */
    public ChatOptions enableThinking(Boolean value) {
        this.enableThinking = value;
        return this;
    }

    public String getModel() {
        return model;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }
}
