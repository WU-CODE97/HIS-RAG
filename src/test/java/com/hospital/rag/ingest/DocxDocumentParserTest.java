package com.hospital.rag.ingest;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOCX 解析器测试：现场用 POI 生成 docx，再交给解析器抽取，验证端到端可用。
 */
class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    @DisplayName("应识别 docx / docm / dotx 后缀（大小写不敏感）")
    void shouldSupportDocxExtension() {
        assertThat(parser.supports("guide.docx")).isTrue();
        assertThat(parser.supports("GUIDE.DOCX")).isTrue();
        assertThat(parser.supports("a.b.c.docx")).isTrue();
        assertThat(parser.supports("form.docm")).isTrue();
        assertThat(parser.supports("template.dotx")).isTrue();
        assertThat(parser.supports("guide.doc")).isFalse();
        assertThat(parser.supports("guide.pdf")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    @Test
    @DisplayName("应提取段落文本")
    void shouldExtractParagraphs() throws Exception {
        byte[] docx = createDocx(doc -> {
            doc.createParagraph().createRun().setText("第一章 工伤结算流程");
            doc.createParagraph().createRun().setText("参保人出院后由医院端发起结算申请。");
        });

        String text = parser.parse(new ByteArrayInputStream(docx));

        assertThat(text).contains("第一章 工伤结算流程");
        assertThat(text).contains("参保人出院后由医院端发起结算申请。");
    }

    @Test
    @DisplayName("应提取表格内容，单元格以 | 分隔")
    void shouldExtractTable() throws Exception {
        byte[] docx = createDocx(doc -> {
            doc.createParagraph().createRun().setText("结算信息表");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段名");
            table.getRow(0).getCell(1).setText("说明");
            table.getRow(1).getCell(0).setText("setl_id");
            table.getRow(1).getCell(1).setText("结算 ID，唯一");
        });

        String text = parser.parse(new ByteArrayInputStream(docx));

        assertThat(text).contains("字段名 | 说明");
        assertThat(text).contains("setl_id | 结算 ID，唯一");
    }

    @Test
    @DisplayName("段落与表格应按原文顺序保留")
    void shouldPreserveDocumentOrder() throws Exception {
        byte[] docx = createDocx(doc -> {
            doc.createParagraph().createRun().setText("表格之前");
            XWPFTable table = doc.createTable(1, 1);
            table.getRow(0).getCell(0).setText("表格内容");
            doc.createParagraph().createRun().setText("表格之后");
        });

        String text = parser.parse(new ByteArrayInputStream(docx));

        assertThat(text.indexOf("表格之前")).isLessThan(text.indexOf("表格内容"));
        assertThat(text.indexOf("表格内容")).isLessThan(text.indexOf("表格之后"));
    }

    @Test
    @DisplayName("空文档应返回空文本，而不是抛异常")
    void shouldReturnBlankForEmptyDocument() throws Exception {
        byte[] docx = createDocx(doc -> {
        });

        assertThat(parser.parse(new ByteArrayInputStream(docx))).isBlank();
    }

    @Test
    @DisplayName("解析结果应压缩多余空白与连续空行")
    void shouldNormalizeWhitespace() throws Exception {
        byte[] docx = createDocx(doc -> {
            doc.createParagraph().createRun().setText("间距    测试   内容");
            doc.createParagraph().createRun().setText("");
            doc.createParagraph().createRun().setText("");
            doc.createParagraph().createRun().setText("");
            doc.createParagraph().createRun().setText("第二段");
        });

        String text = parser.parse(new ByteArrayInputStream(docx));

        assertThat(text).doesNotContain("    ");
        assertThat(text).doesNotContain("\n\n\n");
        assertThat(text).contains("第二段");
    }

    // ---------- 测试用 DOCX 构造 ----------

    private byte[] createDocx(java.util.function.Consumer<XWPFDocument> builder) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            builder.accept(document);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
