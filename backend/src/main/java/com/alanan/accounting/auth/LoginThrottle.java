package com.alanan.accounting.auth;

import com.alanan.accounting.common.exception.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded, per-process protection; the deployment proxy should also limit login traffic. */
@Component
public class LoginThrottle {
    private record Window(Instant expires, int attempts) {}
    private final Map<String, Window> windows = new HashMap<>();
    public synchronized void check(String address) {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(now));
        Window window = windows.get(address);
        if ((window == null && windows.size() >= 10000) || (window != null && window.attempts() >= 20)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED");
        }
        windows.put(address, window == null ? new Window(now.plusSeconds(300), 1)
            : new Window(window.expires(), window.attempts() + 1));
    }
}
