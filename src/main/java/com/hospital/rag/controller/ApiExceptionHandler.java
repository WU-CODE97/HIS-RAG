package com.hospital.rag.controller;

import com.hospital.rag.conversation.ConversationAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一异常映射。
 *
 * <p>目前只处理会话越权：这类错误必须是 403，而不是被包装成 500，
 * 否则调用方无法区分「服务异常」与「无权访问他人会话」。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ConversationAccessException.class)
    public ResponseEntity<Map<String, Object>> handleConversationAccess(ConversationAccessException e) {
        log.warn("会话越权访问: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
