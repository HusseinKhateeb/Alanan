package com.alanan.accounting.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApiErrors {
    private final ObjectMapper mapper;
    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }
    public record Envelope(Instant timestamp, int status, String errorCode, String messageKey,
                           Map<String, String> fieldErrors, String traceId) {}
    public Envelope body(HttpServletRequest request, int status, String code, Map<String, String> fields) {
        return new Envelope(Instant.now(), status, code, "errors." + code, fields,
            (String) request.getAttribute("traceId"));
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), body(request, status, code, Map.of()));
    }
}
