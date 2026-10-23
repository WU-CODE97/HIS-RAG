package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 最终返回结果：答案 + 引用来源 + 运行信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    /** 用户原始问题。 */
    private String query;

    /** 多轮改写后实际用于检索的问题（未改写时与 query 相同）。 */
    private String rewrittenQuery;

    /** 模型生成的答案。 */
    private String answer;

    /** 引用来源列表。 */
    @Builder.Default
    private List<Citation> citations = new ArrayList<>();

    /** 答案是否经过隐私脱敏。 */
    private boolean piiMasked;

    /** 是否命中语义缓存（命中时不会调用检索与生成）。 */
    private boolean cacheHit;

    /** 生成过程是否出错（出错结果不会写入缓存）。 */
    private boolean error;

    /** 端到端耗时（毫秒）。 */
    private long latencyMs;

    /** 引用片段信息。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private String chunkId;
        private String documentId;
        private String documentTitle;
        private int chunkIndex;
        private double score;
        private String snippet;
    }
}
