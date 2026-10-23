package com.hospital.rag.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量分词器，同时服务于 BM25 索引。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>拉丁字母 / 数字：按连续串整体作为一个词，统一小写；</li>
 *   <li>中日韩汉字：按连续串生成「单字 + 相邻二元组（bigram）」，兼顾中文语义与召回。</li>
 * </ul>
 *
 * <p>该实现零依赖、可离线运行；如需更强效果可替换为 HanLP / jieba 等分词器。</p>
 */
@Component
public class Tokenizer {

    /**
     * 将文本切分为词元列表。
     *
     * @param text 原始文本
     * @return 词元列表，文本为空时返回空列表
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String s = text.toLowerCase();
        StringBuilder latin = new StringBuilder();
        List<String> cjkRun = new ArrayList<>();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, tokens);
                cjkRun.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjkRun, tokens);
                latin.append(c);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjkRun, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjkRun, tokens);
        return tokens;
    }

    /** 判断是否为 CJK 汉字。 */
    public boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 基本区
                || (c >= 0x3400 && c <= 0x4DBF) // CJK 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // CJK 兼容区
    }

    private void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private void flushCjk(List<String> run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        for (int i = 0; i < run.size(); i++) {
            tokens.add(run.get(i));
            if (i + 1 < run.size()) {
                tokens.add(run.get(i) + run.get(i + 1));
            }
        }
        run.clear();
    }
}
