package com.hospital.rag.ingest;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DOCX 解析器（基于 Apache POI）。
 *
 * <p>只支持 OOXML 格式（docx / docm / dotx）；老式二进制 .doc 需先另存为 .docx。</p>
 *
 * <p><b>表格必须保留</b>：接口规范、结算指南这类文档的核心信息（字段说明、代码值、参数）
 * 几乎都在表格里，只抽段落会丢掉大半内容。这里按 {@code getBodyElements()} 的顺序遍历，
 * 段落与表格交错写入，行内单元格用 {@code |} 分隔，保证语义与原文位置一致。</p>
 */
@Component
@Order(20)
public class DocxDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxDocumentParser.class);

    private static final Set<String> EXTENSIONS = Set.of(".docx", ".docm", ".dotx");

    /** 表格单元格之间的分隔符。 */
    private static final String CELL_SEPARATOR = " | ";

    @Override
    public boolean supports(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public String parse(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder sb = new StringBuilder();
            int paragraphs = 0;
            int tables = 0;

            // 按文档顺序遍历，段落与表格交错保留
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    sb.append(text.strip()).append('\n');
                    paragraphs++;
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                    tables++;
                }
            }

            String text = normalize(sb.toString());
            if (text.isBlank()) {
                log.warn("DOCX 未提取到任何文本（可能内容全部在文本框或图片中）");
            } else {
                log.info("DOCX 解析完成: {} 段落, {} 表格, {} 字符", paragraphs, tables, text.length());
            }
            return text;
        }
    }

    /** 追加一个表格：每行一条文本，单元格用 {@code |} 分隔。 */
    private void appendTable(StringBuilder sb, XWPFTable table) {
        // 表格前后各留一个空行，切片时更倾向于把整张表当成一个语义块
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append('\n');

        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellText(cell));
            }
            String line = String.join(CELL_SEPARATOR, cells).strip();
            // 跳过整行空白（合并单元格与空行的常见形态）
            if (!line.replace("|", "").isBlank()) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n');
    }

    /** 单元格内可能有多段，拼成一行，避免破坏表格的行结构。 */
    private String cellText(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(text.strip());
        }
        return sb.toString();
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
