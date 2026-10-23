package com.hospital.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * PDF 解析器（基于 Apache PDFBox）。
 *
 * <p>按位置排序抽取文本，适合单栏排版的技术文档与病历模板；
 * 页与页之间用空行分隔，便于后续切分。</p>
 *
 * <p><b>局限</b>：只能提取「文本层」。若 PDF 是扫描件（图片），抽取结果会接近空，
 * 此时需要 OCR（如 Tesseract）预处理，日志中会给出明确提示。</p>
 */
@Component
@Order(1)
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** 低于该字符数时，判定为「可能没有文本层」。 */
    private static final int MIN_MEANINGFUL_CHARS = 20;

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override
    public String parse(InputStream inputStream) throws IOException {
        PDDocument document;
        try {
            document = PDDocument.load(inputStream);
        } catch (InvalidPasswordException e) {
            throw new IOException("PDF 已加密，无法解析", e);
        }

        try (PDDocument doc = document) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            // 按坐标排序，能显著改善多数排版的阅读顺序
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            // 每页末尾补空行，保留分页边界
            stripper.setPageEnd("\n\n");
            stripper.setWordSeparator(" ");

            String raw = stripper.getText(doc);
            String text = normalize(raw);

            if (text.length() < MIN_MEANINGFUL_CHARS) {
                log.warn("PDF 仅提取到 {} 个字符（共 {} 页），可能是扫描件，需要 OCR 预处理", text.length(), pages);
            } else {
                log.info("PDF 解析完成: {} 页, {} 字符", pages, text.length());
            }
            return text;
        }
    }

    /** 统一换行、压缩行内空白、合并多余空行。 */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\f\\u00A0]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
