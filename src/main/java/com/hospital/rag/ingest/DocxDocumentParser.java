package com.hospital.rag.ingest;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX 解析器（基于 Apache POI）。
 *
 * <p>只支持 OOXML 格式（docx / docm / dotx）；老式二进制 .doc 需先另存为 .docx。</p>
 *
 * <p><b>表格必须保留</b>：接口规范、结算指南这类文档的核心信息（字段说明、代码值、参数）
 * 几乎都在表格里，只抽段落会丢掉大半内容。这里按 {@code getBodyElements()} 的顺序遍历，
 * 段落与表格交错写入，行内单元格用 {@code |} 分隔，保证语义与原文位置一致。</p>
 *
 * <p><b>标题层级必须保留</b>：Word 的「标题 1/2/3」样式会转成 Markdown 的 {@code #}/{@code ##}/{@code ###}，
 * 交给 {@link TextSplitter} 按章节切分并给每个片段加上「章节路径」上下文头。
 * 素材再完整，一旦把层级压平成一行，「输出」这一节和它的表格就再也对不上号了。</p>
 */
@Component
@Order(20)
public class DocxDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxDocumentParser.class);

    private static final Set<String> EXTENSIONS = Set.of(".docx", ".docm", ".dotx");

    /** 表格单元格之间的分隔符。 */
    private static final String CELL_SEPARATOR = " | ";

    /** Word 标题样式名（中文模板为「标题 1」，英文模板为「heading 1」）。 */
    private static final Pattern HEADING_NAME = Pattern.compile("^(?:heading|标题)([1-6])$");

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
            int headings = 0;
            boolean afterTable = false;

            // 按文档顺序遍历，段落与表格交错保留
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    int level = headingLevel(document, paragraph);
                    if (level > 0) {
                        sb.append("#".repeat(level)).append(' ').append(text.strip()).append('\n');
                        headings++;
                    } else {
                        sb.append(text.strip()).append('\n');
                    }
                    paragraphs++;
                    afterTable = false;
                } else if (element instanceof XWPFTable table) {
                    // 相邻表格合并：Word 里常见「表头一张表、表体另一张表」，
                    // 拆成两张会得到「只有表头」和「只有表体」两个片段
                    appendTable(sb, table, afterTable);
                    tables++;
                    afterTable = true;
                }
            }

            String text = normalize(sb.toString());
            if (text.isBlank()) {
                log.warn("DOCX 未提取到任何文本（可能内容全部在文本框或图片中）");
            } else {
                log.info("DOCX 解析完成: {} 段落（其中 {} 个标题）, {} 表格, {} 字符",
                        paragraphs, headings, tables, text.length());
            }
            return text;
        }
    }

    /**
     * 追加一个表格：每行一条文本，单元格用 {@code |} 分隔。
     *
     * @param mergeWithPrevious 紧跟在另一张表之后时直接续写行，让表头与表体留在同一个表格块里
     */
    private void appendTable(StringBuilder sb, XWPFTable table, boolean mergeWithPrevious) {
        if (!mergeWithPrevious) {
            // 表格前后各留一个空行，切片时更倾向于把整张表当成一个语义块
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append('\n');
        } else {
            // 续写上一张表：抹掉上一张表结尾的空行，表头行与表体行首尾相接
            while (sb.length() >= 2 && sb.charAt(sb.length() - 1) == '\n'
                    && sb.charAt(sb.length() - 2) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }

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

    /**
     * 段落标题级别：Word 标题样式（heading N / 标题 N）→ 1..6，普通段落返回 0。
     *
     * <p>{@code Title} 样式不算层级：文档标题由入库参数给出，再进章节路径只会让
     * 每个片段的上下文头多拖一段冗余前缀。</p>
     */
    private int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || styleId.isBlank()) {
            return 0;
        }
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            return 0;
        }
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null || style.getName() == null) {
            return 0;
        }
        String name = style.getName().strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher matcher = HEADING_NAME.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
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
