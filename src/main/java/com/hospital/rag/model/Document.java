package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 待入库的原始文档（切分前的完整文本）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    /** 文档唯一标识。 */
    private String id;

    /** 文档标题。 */
    private String title;

    /** 文档正文。 */
    private String content;

    /** 来源（文件名 / 系统 / URL 等）。 */
    private String source;

    /** 扩展元数据（科室、病种、更新时间等）。 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** 访问控制策略。 */
    private AccessControl accessControl;
}
