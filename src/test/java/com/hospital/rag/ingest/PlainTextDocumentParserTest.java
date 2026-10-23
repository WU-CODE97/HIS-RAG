package com.hospital.rag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯文本解析器测试。
 */
class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    @Test
    @DisplayName("应支持 txt / md / csv 等纯文本后缀")
    void shouldSupportTextExtensions() {
        assertThat(parser.supports("a.txt")).isTrue();
        assertThat(parser.supports("a.MD")).isTrue();
        assertThat(parser.supports("a.csv")).isTrue();
        assertThat(parser.supports("a.pdf")).isFalse();
        assertThat(parser.supports("a.docx")).isFalse();
    }

    @Test
    @DisplayName("文件名为空时应作为兜底解析器")
    void shouldFallbackWhenFileNameIsBlank() {
        assertThat(parser.supports(null)).isTrue();
        assertThat(parser.supports("  ")).isTrue();
    }

    @Test
    @DisplayName("应按 UTF-8 正确读出中文内容")
    void shouldReadUtf8Content() throws Exception {
        String content = "高血压患者应低盐饮食。";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        String text = parser.parse(new ByteArrayInputStream(bytes));

        assertThat(text).isEqualTo(content);
    }
}
