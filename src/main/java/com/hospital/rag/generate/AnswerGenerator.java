package com.hospital.rag.generate;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.security.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案生成器：将检索到的片段拼装为上下文，驱动 LLM 生成带引用的答案，并做 PII 脱敏。
 */
@Service
public class AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);

    private static final int SNIPPET_MAX_LEN = 120;

    private static final String SYSTEM_PROMPT = """
            你是医院信息系统（HIS）的智能问答助手，服务于医院内部人员。
            请严格遵守以下规则：
            1. 只依据「参考资料」回答问题，不得编造资料中不存在的信息；
            2. 若资料不足以回答，请明确说明「根据现有资料无法回答」，并指出缺少什么信息；
            3. 引用资料时用 [编号] 标注来源；
            4. 回答需专业、简洁、条理清晰，必要时分点陈述；
            5. 不得输出患者姓名、身份证号、手机号、住院号等个人隐私信息。
            """;

    private final LlmClient llmClient;
    private final PiiMasker piiMasker;
    private final RagProperties.Security securityConfig;

    public AnswerGenerator(LlmClient llmClient, PiiMasker piiMasker, RagProperties properties) {
        this.llmClient = llmClient;
        this.piiMasker = piiMasker;
        this.securityConfig = properties.getSecurity();
    }

    /**
     * 基于检索上下文生成答案。
     *
     * @param query    用户问题
     * @param contexts 检索（重排）后的片段
     * @return 含引用与元信息的 {@link RagAnswer}
     */
    public RagAnswer generate(String query, List<ScoredChunk> contexts) {
        List<RagAnswer.Citation> citations = buildCitations(contexts);
        String userPrompt = buildUserPrompt(query, contexts);

        String answer;
        boolean error = false;
        try {
            answer = llmClient.chat(SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            log.error("答案生成失败", e);
            answer = "抱歉，生成答案时发生错误：" + e.getMessage();
            error = true;
        }

        boolean masked = false;
        if (securityConfig.isMaskPii()) {
            answer = piiMasker.mask(answer);
            masked = true;
        }

        return RagAnswer.builder()
                .query(query)
                .answer(answer)
                .citations(citations)
                .piiMasked(masked)
                .error(error)
                .build();
    }

    private String buildUserPrompt(String query, List<ScoredChunk> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("参考资料：\n");
        if (contexts == null || contexts.isEmpty()) {
            sb.append("（无可用资料）\n");
        } else {
            for (int i = 0; i < contexts.size(); i++) {
                ScoredChunk sc = contexts.get(i);
                String title = sc.getChunk().getDocumentTitle();
                sb.append('[').append(i + 1).append("] ");
                if (title != null && !title.isBlank()) {
                    sb.append("《").append(title).append("》 ");
                }
                sb.append(sc.getChunk().getContent()).append('\n');
            }
        }
        sb.append("\n问题：").append(query).append('\n');
        sb.append("\n请依据上述资料作答，并用 [编号] 标注引用来源。");
        return sb.toString();
    }

    private List<RagAnswer.Citation> buildCitations(List<ScoredChunk> contexts) {
        List<RagAnswer.Citation> citations = new ArrayList<>();
        if (contexts == null) {
            return citations;
        }
        for (ScoredChunk sc : contexts) {
            citations.add(RagAnswer.Citation.builder()
                    .chunkId(sc.getChunk().getId())
                    .documentId(sc.getChunk().getDocumentId())
                    .documentTitle(sc.getChunk().getDocumentTitle())
                    .chunkIndex(sc.getChunk().getIndex())
                    .score(sc.getScore())
                    .snippet(snippet(sc.getChunk().getContent()))
                    .build());
        }
        return citations;
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        return trimmed.length() <= SNIPPET_MAX_LEN ? trimmed : trimmed.substring(0, SNIPPET_MAX_LEN) + "...";
    }
}
