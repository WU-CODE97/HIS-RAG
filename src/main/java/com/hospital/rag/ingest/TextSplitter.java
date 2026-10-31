package com.hospital.rag.ingest;

import com.hospital.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构化文本切分器。
 *
 * <p><b>为什么不能直接按字数硬切。</b>接口规范、结算指南这类文档的核心信息几乎都在表格里，
 * 且表格会被表格行切碎：一张 12 行的参数表，硬切两刀后中间那一片既没有表头也没有「出参」
 * 这类关键词，交叉编码器（cross-encoder）给它打的分会低到 0.006，重排时被直接丢掉，
 * 答案就「少一截」——这正是工伤 Q002「出参有哪些」只答出 4 个参数的原因。</p>
 *
 * <p>所以这里按<b>文档结构</b>切，而不是按字符数切：</p>
 * <ol>
 *   <li>识别标题层级（docx 解析器把 Word 标题样式转成 Markdown 的 {@code #}），
 *       维护章节路径，切片绝不跨章节；</li>
 *   <li>表格按<b>行</b>切，超长表格拆开后每片都<b>重复表头行</b>与表题，
 *       表头与表体不会再分离；</li>
 *   <li>每片正文前加「文档标题 + 章节路径」上下文头，让片段自带出处——
 *       既提升 BM25 / 向量召回的准确率，也让重排模型知道这一片属于哪一节。</li>
 * </ol>
 *
 * <p>没有标题结构的纯文本（PDF、txt）会退化为「按段落 + 按句切」，行为与旧实现一致。</p>
 */
@Component
public class TextSplitter {

    /** 句末标点：超长段落切窗口时优先在这些位置断开。 */
    private static final String SENTENCE_END = "。！？!?；;";

    /** 表题前缀，用于把「表 6 输出-基本信息」这类段落吸附到紧邻的表格上。 */
    private static final String CAPTION_PATTERN = "^(表|图|Table|Figure)\\s*[0-9IVXivx一二三四五六七八九十]+";

    /** 表题最大长度；超过说明是正文而非表题。 */
    private static final int CAPTION_MAX_LEN = 60;

    /** 上下文头的起始标记。 */
    private static final String HEADER_START = "【文档：";

    private final RagProperties.Chunking config;

    public TextSplitter(RagProperties properties) {
        this.config = properties.getChunk();
    }

    /**
     * 按结构切分文本。
     *
     * @param text          文档正文（解析器输出，保留换行与表格行）
     * @param documentTitle 文档标题，用于生成上下文头
     * @return 切片列表（有序，正文已带上下文头）
     */
    public List<TextPiece> split(String text, String documentTitle) {
        List<TextPiece> pieces = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return pieces;
        }
        if (!config.isStructureAware()) {
            return flattenedSplit(text, documentTitle);
        }
        List<Block> blocks = parseBlocks(text);
        attachCaptions(blocks);

        Cursor cursor = new Cursor(documentTitle, pieces);
        SectionPath path = new SectionPath();
        for (Block block : blocks) {
            switch (block.kind) {
                case HEADING -> {
                    cursor.flush();
                    path.push(block.level, block.text);
                }
                case TEXT -> cursor.appendText(block.text, path.current());
                case TABLE -> cursor.appendTable(block, path.current());
            }
        }
        cursor.flush();
        return mergeTiny(pieces);
    }

    /** 兼容旧调用：只取正文，不带文档标题。 */
    public List<String> split(String text) {
        List<String> texts = new ArrayList<>();
        for (TextPiece piece : split(text, null)) {
            texts.add(piece.content());
        }
        return texts;
    }

    /** 退路：{@code structure-aware=false} 时等价于旧实现（压平空白后按字数滑窗）。 */
    private List<TextPiece> flattenedSplit(String text, String documentTitle) {
        List<TextPiece> pieces = new ArrayList<>();
        Cursor cursor = new Cursor(documentTitle, pieces);
        cursor.appendText(text.replaceAll("\\s+", " ").trim(), "");
        cursor.flush();
        return pieces;
    }

    /**
     * 合并过短片段。
     *
     * <p>「工伤医院端结算提供的22个接口列表如下：」这类导语只有一句话，单独成片后
     * 既召不回也重排不上，并到同章节的下一片里更有用。</p>
     */
    private List<TextPiece> mergeTiny(List<TextPiece> pieces) {
        int min = Math.max(60, size() / 3);
        List<TextPiece> merged = new ArrayList<>(pieces.size());
        for (TextPiece piece : pieces) {
            if (!merged.isEmpty()) {
                TextPiece prev = merged.get(merged.size() - 1);
                if (Objects.equals(prev.section(), piece.section())
                        && bodyOf(prev).length() < min
                        && bodyOf(prev).length() + bodyOf(piece).length() + 1 <= size()) {
                    merged.set(merged.size() - 1, combine(prev, piece));
                    continue;
                }
            }
            merged.add(piece);
        }
        return merged;
    }

    private TextPiece combine(TextPiece prev, TextPiece next) {
        String header = headerOf(next);
        String body = bodyOf(prev) + "\n" + bodyOf(next);
        return new TextPiece(header.isEmpty() ? body : header + "\n" + body, next.section());
    }

    private String headerOf(TextPiece piece) {
        String content = piece.content();
        if (!content.startsWith(HEADER_START)) {
            return "";
        }
        int lineEnd = content.indexOf('\n');
        return lineEnd < 0 ? content : content.substring(0, lineEnd);
    }

    private String bodyOf(TextPiece piece) {
        String content = piece.content();
        if (!content.startsWith(HEADER_START)) {
            return content;
        }
        int lineEnd = content.indexOf('\n');
        return lineEnd < 0 ? "" : content.substring(lineEnd + 1).strip();
    }

    // ------------------------------------------------------------------
    // 解析：把文本拆成 标题 / 段落 / 表格 三类块
    // ------------------------------------------------------------------

    private List<Block> parseBlocks(String raw) {
        List<Block> blocks = new ArrayList<>();
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        StringBuilder paragraph = new StringBuilder();
        List<String> tableRows = null;

        for (String rawLine : normalized.split("\n")) {
            String line = collapse(rawLine);
            if (line.isEmpty()) {
                if (flushParagraph(blocks, paragraph)) {
                    // 段落已收尾
                }
                tableRows = flushTable(blocks, tableRows);
                continue;
            }

            int level = headingLevel(line);
            if (level > 0) {
                int start = level;
                while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
                    start++;
                }
                String title = line.substring(start).strip();
                flushParagraph(blocks, paragraph);
                tableRows = flushTable(blocks, tableRows);
                // 套了标题样式但其实是正文句子：降级为普通段落，但井号必须去掉
                if (looksLikeSentence(title)) {
                    blocks.add(Block.text(title));
                } else {
                    blocks.add(Block.heading(level, title));
                }
                continue;
            }

            if (isTableRow(line)) {
                flushParagraph(blocks, paragraph);
                if (tableRows == null) {
                    tableRows = new ArrayList<>();
                }
                tableRows.add(line);
                continue;
            }

            tableRows = flushTable(blocks, tableRows);
            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(line);
        }

        flushParagraph(blocks, paragraph);
        flushTable(blocks, tableRows);
        return blocks;
    }

    /** 行内空白压缩，换行与表格结构保留。 */
    private String collapse(String line) {
        return line.replaceAll("[ \\t\\f\\u00A0]+", " ").strip();
    }

    /** 标题级别（1..6，即井号个数），不是标题行返回 0。 */
    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6) {
            return 0;
        }
        // 必须是「# 标题」形式；「#标签」不算
        if (level >= line.length() || !Character.isWhitespace(line.charAt(level))) {
            return 0;
        }
        return level;
    }

    /** 标题是否其实是正文句子（长度偏长且以句末标点结尾）。 */
    private boolean looksLikeSentence(String title) {
        return title.length() > 8 && !title.isEmpty()
                && "。！？.!?；;".indexOf(title.charAt(title.length() - 1)) >= 0;
    }

    private boolean isTableRow(String line) {
        return line.indexOf('|') >= 0 && line.contains(" | ");
    }

    private boolean flushParagraph(List<Block> blocks, StringBuilder paragraph) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(Block.text(text));
            return true;
        }
        return false;
    }

    private List<String> flushTable(List<Block> blocks, List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() >= 2) {
            blocks.add(Block.table(new ArrayList<>(rows)));
        } else {
            // 只有一行含分隔符，多半是正文里的「A | B」写法，按段落处理
            blocks.add(Block.text(rows.get(0)));
        }
        rows.clear();
        return null;
    }

    /** 把紧邻表格的表题段落吸附到表格上，切分时随表头一起重复。 */
    private void attachCaptions(List<Block> blocks) {
        for (int i = 1; i < blocks.size(); i++) {
            Block prev = blocks.get(i - 1);
            Block next = blocks.get(i);
            if (next.kind == Kind.TABLE && next.caption == null
                    && prev.kind == Kind.TEXT && isCaption(prev.text)) {
                next.caption = prev.text;
                blocks.remove(i - 1);
                i--;
            }
        }
    }

    private boolean isCaption(String text) {
        return text != null && text.length() <= CAPTION_MAX_LEN
                && text.matches(CAPTION_PATTERN + ".*");
    }

    // ------------------------------------------------------------------
    // 拼装：把块合并成不超过 size 的片段
    // ------------------------------------------------------------------

    private int size() {
        return Math.max(80, config.getSize());
    }

    /** 单次切分过程的游标：负责缓存当前片段、处理溢出与上下文头。 */
    private final class Cursor {

        private final String documentTitle;
        private final List<TextPiece> pieces;
        private final StringBuilder buffer = new StringBuilder();
        private String bufferSection = "";

        Cursor(String documentTitle, List<TextPiece> pieces) {
            this.documentTitle = documentTitle;
            this.pieces = pieces;
        }

        void appendText(String text, String section) {
            if (buffer.length() > 0 && buffer.length() + text.length() + 1 > size()) {
                flush();
            }
            if (text.length() > size()) {
                flush();
                for (String window : windowSplit(text)) {
                    pieces.add(new TextPiece(decorate(window, section), section));
                }
                return;
            }
            if (buffer.length() == 0) {
                bufferSection = section;
            }
            buffer.append(text).append('\n');
        }

        void appendTable(Block table, String section) {
            String rendered = renderTable(table);
            if (rendered.length() <= size()) {
                appendText(rendered, section);
                return;
            }
            // 大表：按行拆，每片重复表头 + 表题
            flush();
            emitTablePieces(table, section);
        }

        void flush() {
            String content = buffer.toString().strip();
            buffer.setLength(0);
            if (!content.isEmpty()) {
                pieces.add(new TextPiece(decorate(content, bufferSection), bufferSection));
            }
        }

        /** 整表渲染：表题在首行，其余为表格行。 */
        private String renderTable(Block table) {
            StringBuilder sb = new StringBuilder();
            if (table.caption != null) {
                sb.append(table.caption).append('\n');
            }
            for (String row : table.rows) {
                sb.append(row).append('\n');
            }
            return sb.toString().strip();
        }

        private void emitTablePieces(Block table, String section) {
            List<String> rows = table.rows;
            String header = rows.get(0);
            int budget = size();

            StringBuilder current = new StringBuilder();
            if (table.caption != null) {
                current.append(table.caption).append('\n');
            }
            current.append(header).append('\n');
            int dataRows = 0;

            for (int i = 1; i < rows.size(); i++) {
                String row = rows.get(i);
                if (dataRows > 0 && current.length() + row.length() + 1 > budget) {
                    pieces.add(new TextPiece(decorate(current.toString(), section), section));
                    current.setLength(0);
                    if (table.caption != null) {
                        current.append(table.caption).append('\n');
                    }
                    // 关键：每片都带上表头，表体不会失去列语义
                    current.append(header).append('\n');
                    dataRows = 0;
                }
                current.append(row).append('\n');
                dataRows++;
            }
            if (dataRows > 0) {
                pieces.add(new TextPiece(decorate(current.toString(), section), section));
            }
        }

        /** 超长段落按句末标点开窗，保留少量重叠。 */
        private List<String> windowSplit(String text) {
            List<String> windows = new ArrayList<>();
            int len = text.length();
            int budget = size();
            int overlap = Math.max(0, Math.min(config.getOverlap(), budget / 2));
            int start = 0;
            while (start < len) {
                int end = Math.min(start + budget, len);
                if (end < len) {
                    int boundary = findBoundary(text, start, end, budget);
                    if (boundary > start) {
                        end = boundary;
                    }
                }
                String piece = text.substring(start, end).strip();
                if (!piece.isEmpty()) {
                    windows.add(piece);
                }
                if (end >= len) {
                    break;
                }
                start = Math.max(end - overlap, start + 1);
            }
            return windows;
        }

        private int findBoundary(String text, int start, int end, int budget) {
            int lowerBound = start + budget / 2;
            for (int i = end - 1; i >= lowerBound; i--) {
                if (SENTENCE_END.indexOf(text.charAt(i)) >= 0) {
                    return i + 1;
                }
            }
            return -1;
        }

        /** 上下文头：让片段自带「出自哪份文档的哪一节」。 */
        private String decorate(String content, String section) {
            if (!config.isContextHeader()) {
                return content;
            }
            StringBuilder sb = new StringBuilder("【文档：");
            sb.append(documentTitle == null || documentTitle.isBlank() ? "未命名文档" : documentTitle.strip());
            if (section != null && !section.isBlank()) {
                sb.append("｜章节：").append(section);
            }
            sb.append("】\n").append(content);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    private enum Kind { HEADING, TEXT, TABLE }

    private static final class Block {
        private final Kind kind;
        private int level;
        private String text;
        private List<String> rows;
        private String caption;

        private Block(Kind kind) {
            this.kind = kind;
        }

        static Block heading(int level, String text) {
            Block block = new Block(Kind.HEADING);
            block.level = level;
            block.text = text;
            return block;
        }

        static Block text(String text) {
            Block block = new Block(Kind.TEXT);
            block.text = text;
            return block;
        }

        static Block table(List<String> rows) {
            Block block = new Block(Kind.TABLE);
            block.rows = rows;
            return block;
        }
    }

    /** 章节路径栈：{@code # a} → {@code ## b} 时路径为 {@code a > b}。 */
    private static final class SectionPath {

        private final List<String> segments = new ArrayList<>();

        void push(int level, String title) {
            while (segments.size() >= level) {
                segments.remove(segments.size() - 1);
            }
            if (title != null && !title.isBlank()) {
                segments.add(title.strip());
            }
        }

        String current() {
            return String.join(" > ", segments);
        }
    }
}
