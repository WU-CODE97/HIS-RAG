package com.hospital.rag.security;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式输出的增量脱敏器。
 *
 * <p>脱敏依赖完整的文本匹配，而流式输出的分片边界由上游模型决定，可能正好落在
 * 一个身份证号或卡号中间。若逐片脱敏，被切开的号码两段都匹配不上，等于漏脱敏。</p>
 *
 * <p>这里的策略是「按句切分 + 尾部保留窗口」：</p>
 * <ul>
 *   <li>优先在句末标点处切分——标点不是 PII 的组成部分，因此不可能切断号码；</li>
 *   <li>若长时间没有标点（如表格、代码块），则保留末尾 {@code tailGuard} 个字符不下发，
 *       长度大于 18 位身份证 / 19 位卡号即可保证号码完整地落在已下发部分里。</li>
 * </ul>
 *
 * <p>本类非线程安全，每次请求新建一个实例。</p>
 */
public class StreamingPiiMasker {

    /** 句末切分点：中英文标点与换行。 */
    private static final String TERMINATORS = "。！？；!?;\n\r";

    /** 无标点时最多缓冲的字符数，超过后按尾部窗口强制下发，避免长时间无输出。 */
    private static final int MAX_HOLD = 200;

    private final PiiMasker masker;
    private final int tailGuard;
    private final StringBuilder buffer = new StringBuilder();
    private boolean masked;

    public StreamingPiiMasker(PiiMasker masker, int tailGuard) {
        this.masker = masker;
        this.tailGuard = Math.max(0, tailGuard);
    }

    /**
     * 追加一段模型输出，返回可以立即下发的、已脱敏的文本片段。
     */
    public List<String> accept(String delta) {
        List<String> ready = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return ready;
        }
        buffer.append(delta);

        while (true) {
            int cut = findCut();
            if (cut <= 0) {
                break;
            }
            String segment = buffer.substring(0, cut);
            buffer.delete(0, cut);
            emit(ready, segment);
        }
        return ready;
    }

    /**
     * 结束输出，返回缓冲区中剩余的、已脱敏的文本。
     */
    public String finish() {
        if (buffer.length() == 0) {
            return "";
        }
        String rest = buffer.toString();
        buffer.setLength(0);
        String maskedText = masker.mask(rest);
        if (!maskedText.equals(rest)) {
            masked = true;
        }
        return maskedText;
    }

    /** 本次输出中是否实际发生了脱敏替换。 */
    public boolean isMasked() {
        return masked;
    }

    private void emit(List<String> ready, String segment) {
        if (segment.isEmpty()) {
            return;
        }
        String maskedText = masker.mask(segment);
        if (!maskedText.equals(segment)) {
            masked = true;
        }
        ready.add(maskedText);
    }

    /**
     * 计算本次可下发的切分位置；返回 0 表示继续缓冲。
     */
    private int findCut() {
        int length = buffer.length();
        for (int i = length - 1; i >= 0; i--) {
            if (TERMINATORS.indexOf(buffer.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        if (length > MAX_HOLD) {
            return length - tailGuard;
        }
        return 0;
    }
}
