package com.hospital.rag.config;

import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.retrieve.HttpReranker;
import com.hospital.rag.retrieve.LlmReranker;
import com.hospital.rag.retrieve.PassThroughReranker;
import com.hospital.rag.retrieve.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排器装配：按 {@code rag.rerank.provider} 选择实现。
 *
 * <p>之所以集中在这里装配，而不是给每个实现打 {@code @Component}，
 * 是因为容器里必须<b>只有一个</b> {@link Reranker} 候选，
 * 否则 {@code RagService} 注入时会因多实例而启动失败。</p>
 */
@Configuration
public class RerankConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RerankConfiguration.class);

    @Bean
    public Reranker reranker(RagProperties properties, LlmClient llmClient) {
        RagProperties.Rerank config = properties.getRerank();
        String provider = config.getProvider() == null ? "gte" : config.getProvider().trim().toLowerCase();

        switch (provider) {
            case "none":
                log.info("重排已关闭（provider=none），直接使用融合后的召回顺序");
                return new PassThroughReranker();
            case "llm":
                log.info("重排实现: LLM 列表式（耗时较高，仅建议调试或小流量场景）");
                return new LlmReranker(llmClient);
            case "gte":
            default:
                Reranker fallback = config.isFailOpenToLlm() ? new LlmReranker(llmClient) : null;
                log.info("重排实现: 专用 cross-encoder，model={}，候选上限={}，超时={}ms{}",
                        config.getModel(), config.getMaxCandidates(), config.getTimeoutMs(),
                        fallback == null ? "" : "，故障时降级到 LLM 重排");
                return new HttpReranker(config, fallback);
        }
    }
}
