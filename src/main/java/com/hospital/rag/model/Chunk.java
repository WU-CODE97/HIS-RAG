package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档切分后的最小检索单元。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    /** 片段唯一标识，约定为 {@code documentId#index}。 */
    private String id;

    /** 所属文档 id。 */
    private String documentId;

    /** 所属文档标题（便于生成引用）。 */
    private String documentTitle;

    /** 片段正文。 */
    private String content;

    /** 片段所在章节路径，形如 {@code 医保接口开发差异 > 新增交易【Q002】... > 输出}。 */
    private String section;

    /** 片段在文档中的序号（从 0 开始）。 */
    private int index;

    /** 从文档继承的元数据。 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** 从文档继承的访问控制策略。 */
    private AccessControl accessControl;

    /**
     * 判断该片段对某用户是否可见。
     */
    public boolean isAccessible(UserContext user) {
        return accessControl == null || accessControl.isAccessible(user);
    }
}
