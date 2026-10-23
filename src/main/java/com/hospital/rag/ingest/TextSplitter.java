package com.hospital.rag.ingest;

import com.hospital.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器。
 *
 * <p>按配置的窗口大小滑动切分，并尽量在句末标点处断开，避免截断语义；
 * 相邻切片保留固定重叠，缓解边界信息丢失。</p>
 */
@Component
public class TextSplitter {

    private static final String SENTENCE_END = "。！？!?；;\n";

    private final RagProperties.Chunking config;

    public TextSplitter(RagProperties properties) {
        this.config = properties.getChunk();
    }

    /**
     * 切分文本。
     *
     * @param text 原始文本
     * @return 切片列表（已去除首尾空白，空切片会被过滤）
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int len = normalized.length();
        int size = Math.max(50, config.getSize());
        int overlap = Math.max(0, Math.min(config.getOverlap(), size / 2));

        int start = 0;
        while (start < len) {
            int end = Math.min(start + size, len);
            if (end < len) {
                int boundary = findBoundary(normalized, start, end, size);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= len) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    /**
     * 在 [start + size/2, end] 区间内寻找最后一个句末标点位置。
     */
    private int findBoundary(String text, int start, int end, int size) {
        int lowerBound = start + size / 2;
        for (int i = end - 1; i >= lowerBound; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
