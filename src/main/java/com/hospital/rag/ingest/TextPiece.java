package com.hospital.rag.ingest;

/**
 * 结构化切片结果。
 *
 * @param content 片段正文（已带上「文档 + 章节」上下文头）
 * @param section 片段所属章节路径，形如 {@code 医保接口开发差异 > 新增交易【Q002】... > 输出}；
 *                无标题结构的文档为空串
 */
public record TextPiece(String content, String section) {
}
