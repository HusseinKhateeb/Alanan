package com.alanan.accounting.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final RefreshSessions refreshSessions;
    private final boolean secureCookie;
    public AuthController(AuthService auth, RefreshSessions refreshSessions, @Value("${app.auth.refresh-cookie-secure:false}") boolean secureCookie) {
        this.auth = auth; this.refreshSessions = refreshSessions; this.secureCookie = secureCookie;
    }
    public record Login(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "Login[REDACTED]"; }
    }
    public record Language(@NotBlank @Pattern(regexp = "ar|en") String language) {}
    @PostMapping("/login")
    TokenService.Token login(@Valid @RequestBody Login request, HttpServletRequest servlet, HttpServletResponse response) {
        var issued = auth.login(request.username(), request.password(), servlet.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(issued.refreshToken(), RefreshSessions.LIFETIME.getSeconds()));
        return issued.access();
    }
    @PostMapping("/refresh")
    TokenService.Token refresh(@RequestHeader(value = "X-Alanan-Session", required = false) String sessionHeader,
            @CookieValue(value = "alanan_refresh", required = false) String cookie, HttpServletResponse response) {
        if (!"refresh".equals(sessionHeader)) throw new com.alanan.accounting.common.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN");
        var issued = refreshSessions.rotate(cookie);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(issued.refreshToken(), RefreshSessions.LIFETIME.getSeconds()));
        return issued.access();
    }
    @GetMapping("/me")
    AuthUser me(@AuthenticationPrincipal AuthUser user) { return user; }
    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal AuthUser user, HttpServletResponse response) {
        auth.logout(user.id());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0));
    }
    private String cookie(String token, long maxAge) {
        return ResponseCookie.from("alanan_refresh", token).httpOnly(true).secure(secureCookie)
            .sameSite("Strict").path("/api/auth").maxAge(maxAge).build().toString();
    }
    @PutMapping("/language")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void language(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody Language request) {
        auth.language(user.id(), request.language());
    }
}
