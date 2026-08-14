package com.ai.aiagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chuyen exception thanh JSON on dinh.
 *
 * Quan trong: KHONG tra {@code e.getMessage()} cua loi khong luong truoc ra client -
 * truoc day cach lam do lam lo cau SQL, ten bang va host DB. Thay vao do sinh mot
 * {@code traceId}, ghi chi tiet vao log va chi tra traceId cho nguoi dung.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Du lieu gui len khong hop le.");
        return body(HttpStatus.BAD_REQUEST, msg, null);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage(), null);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE,
                "File vuot qua gioi han upload cua he thong.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Loi khong luong truoc [traceId={}]", traceId, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "Da xay ra loi he thong. Vui long gui ma tra cuu cho quan tri vien.", traceId);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null ? status.getReasonPhrase() : message);
        m.put("status", status.value());
        if (traceId != null) m.put("traceId", traceId);
        return ResponseEntity.status(status).body(m);
    }
}
