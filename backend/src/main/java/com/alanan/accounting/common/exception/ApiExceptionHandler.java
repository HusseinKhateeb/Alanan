package com.alanan.accounting.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final ApiErrors errors;
    public ApiExceptionHandler(ApiErrors errors) { this.errors = errors; }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> application(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(errors.body(request, ex.status().value(), ex.code(), Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var fields = new TreeMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(error -> fields.put(error.getField(), "errors.INVALID_FIELD"));
        return ResponseEntity.badRequest().body(errors.body(request, 400, "VALIDATION_FAILED", fields));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(errors.body(request, 400, "VALIDATION_FAILED", Map.of()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(errors.body(request, 403, "FORBIDDEN", Map.of()));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        Throwable cause = ex.getMostSpecificCause();
        boolean duplicate = cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
        String code = duplicate ? (request.getRequestURI().startsWith("/api/customers") ? "CUSTOMER_PHONE_EXISTS" : "DUPLICATE_RECORD") : "VALIDATION_FAILED";
        int status = duplicate ? 409 : 400;
        return ResponseEntity.status(status).body(errors.body(request, status, code,
            "CUSTOMER_PHONE_EXISTS".equals(code) ? Map.of("phone", "errors.CUSTOMER_PHONE_EXISTS") : Map.of()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        // Do not log exception messages that may contain credentials or SQL parameters.
        LoggerFactory.getLogger(getClass()).error("Request failed traceId={} type={}", request.getAttribute("traceId"), ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(errors.body(request, 500, "SERVER_ERROR", Map.of()));
    }
}
