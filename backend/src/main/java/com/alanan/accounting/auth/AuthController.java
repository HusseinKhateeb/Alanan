package com.alanan.accounting.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    public record Login(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "Login[REDACTED]"; }
    }
    public record Language(@NotBlank @Pattern(regexp = "ar|en") String language) {}
    @PostMapping("/login")
    TokenService.Token login(@Valid @RequestBody Login request, HttpServletRequest servlet) {
        return auth.login(request.username(), request.password(), servlet.getRemoteAddr());
    }
    @GetMapping("/me")
    AuthUser me(@AuthenticationPrincipal AuthUser user) { return user; }
    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal AuthUser user) { auth.logout(user.id()); }
    @PutMapping("/language")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void language(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody Language request) {
        auth.language(user.id(), request.language());
    }
}
