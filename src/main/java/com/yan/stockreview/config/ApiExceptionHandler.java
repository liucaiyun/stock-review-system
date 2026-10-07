package com.yan.stockreview.config;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null ? "参数错误" : ex.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> upstream(IllegalStateException ex) {
        String msg = ex.getMessage() == null ? "操作失败" : ex.getMessage();
        if (msg.contains("已在自选") || msg.contains("已有持仓") || msg.contains("超过上限")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", msg));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception ex) {
        String msg = ex.getMessage() == null ? "服务器错误" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", msg));
    }
}
