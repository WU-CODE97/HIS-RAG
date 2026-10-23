package com.hospital.rag.ingest;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 纯文本解析器（txt / md / csv / log），同时作为「无后缀」场景的兜底实现。
 */
@Component
@Order(100)
public class PlainTextDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of(".txt", ".md", ".markdown", ".csv", ".log");

    @Override
    public boolean supports(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public String parse(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
