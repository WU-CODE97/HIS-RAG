package com.hospital.rag.ingest;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析器抽象：把各种格式的文件转换为纯文本，供后续切分与向量化。
 *
 * <p>按 {@link #supports(String)} 匹配文件类型；同一份文件只会交给第一个匹配的解析器。
 * 新增格式（如 docx）只需实现本接口并注册为 Bean，无需改动入库主流程。</p>
 */
public interface DocumentParser {

    /**
     * 是否支持该文件。
     *
     * @param fileName 文件名（含后缀），可为 null
     */
    boolean supports(String fileName);

    /**
     * 解析为纯文本。
     *
     * @param inputStream 文件流（由调用方负责关闭）
     * @return 提取到的文本；无法提取时返回空串而非抛异常
     */
    String parse(InputStream inputStream) throws IOException;

    /** 解析器名称，用于日志。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
