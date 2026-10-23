package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 带评分的检索结果。
 *
 * <p>保留各阶段得分（向量分 / BM25 分 / 融合分 / 重排分）便于调试与评估。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredChunk {

    /** 命中的片段。 */
    private Chunk chunk;

    /** 当前阶段的主评分（越大越相关）。 */
    private double score;

    /** 评分来源：vector / bm25 / hybrid / rerank。 */
    private String source;

    /** 向量相似度得分。 */
    private double vectorScore;

    /** BM25 得分。 */
    private double bm25Score;

    /** 排名（从 1 开始）。 */
    private int rank;

    public static ScoredChunk of(Chunk chunk, double score, String source) {
        return ScoredChunk.builder().chunk(chunk).score(score).source(source).build();
    }
}
