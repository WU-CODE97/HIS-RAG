package com.hospital.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 解析器测试：现场用 PDFBox 生成 PDF，再交给解析器抽取，验证端到端可用。
 */
class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    @DisplayName("应识别 .pdf 后缀（大小写不敏感）")
    void shouldSupportPdfExtension() {
        assertThat(parser.supports("guide.pdf")).isTrue();
        assertThat(parser.supports("GUIDE.PDF")).isTrue();
        assertThat(parser.supports("a.b.c.pdf")).isTrue();
        assertThat(parser.supports("guide.txt")).isFalse();
        assertThat(parser.supports("guide.docx")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    @Test
    @DisplayName("应从单页 PDF 中提取文本")
    void shouldExtractTextFromSinglePage() throws Exception {
        byte[] pdf = createPdf("ACEI is contraindicated in pregnancy.");

        String text = parser.parse(new ByteArrayInputStream(pdf));

        assertThat(text).contains("ACEI");
        assertThat(text).contains("contraindicated in pregnancy");
    }

    @Test
    @DisplayName("多页 PDF 应保留全部页面内容，且页间有空行")
    void shouldExtractTextFromMultiPage() throws Exception {
        byte[] pdf = createPdf("First page content about hypertension.", "Second page content about diabetes.");

        String text = parser.parse(new ByteArrayInputStream(pdf));

        assertThat(text).contains("hypertension");
        assertThat(text).contains("diabetes");
        assertThat(text).contains("\n\n");
    }

    @Test
    @DisplayName("无文本层的 PDF 应返回空文本，而不是抛异常")
    void shouldReturnBlankForPdfWithoutText() throws Exception {
        byte[] pdf = createEmptyPdf();

        String text = parser.parse(new ByteArrayInputStream(pdf));

        assertThat(text).isBlank();
    }

    @Test
    @DisplayName("解析结果应压缩多余空白与连续空行")
    void shouldNormalizeWhitespace() throws Exception {
        byte[] pdf = createPdf("Spacing    test   content");

        String text = parser.parse(new ByteArrayInputStream(pdf));

        assertThat(text).doesNotContain("    ");
    }

    // ---------- 测试用 PDF 构造 ----------

    private byte[] createPdf(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(pageText);
                    cs.endText();
                }
            }
            return toBytes(document);
        }
    }

    private byte[] createEmptyPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            return toBytes(document);
        }
    }

    private byte[] toBytes(PDDocument document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
