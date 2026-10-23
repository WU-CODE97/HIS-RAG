package com.hospital.rag.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 隐私信息（PII）脱敏器。
 *
 * <p>基于正则对常见个人敏感信息做替换，用于模型输出与日志的落地保护。
 * 顺序敏感：先长后短，避免身份证号被卡号规则截断。</p>
 */
@Component
public class PiiMasker {

    /** 身份证号（18 位，末位可为 X）。 */
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");

    /** 银行卡号（16~19 位连续数字）。 */
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 手机号。 */
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 固定电话。 */
    private static final Pattern LANDLINE =
            Pattern.compile("(?<!\\d)0\\d{2,3}-?\\d{7,8}(?!\\d)");

    /** 电子邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 对文本执行脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本；入参为 null 时原样返回
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        masked = ID_CARD.matcher(masked).replaceAll("[身份证号已脱敏]");
        masked = BANK_CARD.matcher(masked).replaceAll("[卡号已脱敏]");
        masked = PHONE.matcher(masked).replaceAll("[手机号已脱敏]");
        masked = LANDLINE.matcher(masked).replaceAll("[电话已脱敏]");
        masked = EMAIL.matcher(masked).replaceAll("[邮箱已脱敏]");
        return masked;
    }

    /**
     * 判断文本中是否包含疑似 PII。
     */
    public boolean containsPii(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return ID_CARD.matcher(text).find()
                || PHONE.matcher(text).find()
                || EMAIL.matcher(text).find()
                || BANK_CARD.matcher(text).find();
    }
}
