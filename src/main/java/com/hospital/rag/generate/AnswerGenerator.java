package com.hospital.rag.generate;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.llm.ChatOptions;
import com.hospital.rag.llm.LlmClient;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.security.PiiMasker;
import com.hospital.rag.security.StreamingPiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

    /**
     * 流式生成答案：每产出一段正文就回调 {@code onDelta}，同时累积完整答案用于缓存与会话历史。
     *
     * <p>脱敏仍是「先脱敏再下发」：{@link StreamingPiiMasker} 会按句切分并保留尾部窗口，
     * 保证被派发出去的文本里不会出现未脱敏的证件号 / 卡号。</p>
     *
     * @param query    用户问题（改写后）
     * @param contexts 检索（重排）后的片段
     * @param onDelta  增量回调，入参已脱敏
     * @return 与 {@link #generate} 等价的完整答案对象
     */
    public RagAnswer generateStreaming(String query, List<ScoredChunk> contexts, Consumer<String> onDelta) {
        List<RagAnswer.Citation> citations = buildCitations(contexts);
        String userPrompt = buildUserPrompt(query, contexts);
        boolean maskEnabled = securityConfig.isMaskPii();
        StreamingPiiMasker streamMasker = maskEnabled
                ? new StreamingPiiMasker(piiMasker, securityConfig.getPiiTailGuard())
                : null;

        StringBuilder answer = new StringBuilder();
        boolean error = false;
        try {
            llmClient.streamChat(SYSTEM_PROMPT, userPrompt, ChatOptions.create(), chunk -> {
                if (streamMasker == null) {
                    answer.append(chunk);
                    onDelta.accept(chunk);
                    return;
                }
                for (String segment : streamMasker.accept(chunk)) {
                    answer.append(segment);
                    onDelta.accept(segment);
                }
            });
            if (streamMasker != null) {
                String tail = streamMasker.finish();
                if (!tail.isEmpty()) {
                    answer.append(tail);
                    onDelta.accept(tail);
                }
            }
        } catch (Exception e) {
            log.error("流式生成失败", e);
            error = true;
            String notice = answer.length() == 0
                    ? "抱歉，生成答案时发生错误：" + e.getMessage()
                    : "\n\n[输出中断：" + e.getMessage() + "]";
            answer.append(notice);
            onDelta.accept(notice);
        }

        return RagAnswer.builder()
                .query(query)
                .answer(answer.toString())
                .citations(citations)
                .piiMasked(maskEnabled)
                .error(error)
                .build();
    }

    /** 把检索片段映射为对外引用列表（流式接口需要在生成前先把引用发给前端）。 */
    public List<RagAnswer.Citation> citations(List<ScoredChunk> contexts) {
        return buildCitations(contexts);
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
                String content = sc.getChunk().getContent();
                sb.append('[').append(i + 1).append("] ");
                // 结构化切片已把「文档 + 章节」写进正文头，这里不再重复标题
                if (!startsWithContextHeader(content) && title != null && !title.isBlank()) {
                    sb.append("《").append(title).append("》 ");
                }
                sb.append(content).append('\n');
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
                    .section(sc.getChunk().getSection())
                    .chunkIndex(sc.getChunk().getIndex())
                    .score(sc.getScore())
                    .snippet(snippet(sc.getChunk().getContent()))
                    .build());
        }
        return citations;
    }

    /** 片段正文是否以「【文档：…｜章节：…】」上下文头开头。 */
    private boolean startsWithContextHeader(String content) {
        return content != null && content.stripLeading().startsWith("【文档：");
    }

    /** 引用摘要：去掉上下文头，避免引用列表里每一条都以同一段前缀开头。 */
    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        if (startsWithContextHeader(trimmed)) {
            // 头是一整行，按行切；章节名里可能还有「【Q002】」这种全角括号，不能按 】 定位
            int lineEnd = trimmed.indexOf('\n');
            if (lineEnd > 0) {
                trimmed = trimmed.substring(lineEnd + 1).strip();
            }
        }
        return trimmed.length() <= SNIPPET_MAX_LEN ? trimmed : trimmed.substring(0, SNIPPET_MAX_LEN) + "...";
    }
}
