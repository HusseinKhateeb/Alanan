package com.alanan.accounting.common.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class RequestFingerprint {
    private final ObjectMapper json;
    public RequestFingerprint(ObjectMapper json) { this.json=json; }
    public String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize transaction",ex); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(value).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
