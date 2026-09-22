package com.alanan.accounting.auth;

import com.alanan.accounting.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final LoginThrottle throttle;
    private final String dummyHash;
    public AuthService(IdentityRepository identities, PasswordEncoder passwords, TokenService tokens, LoginThrottle throttle) {
        this.identities = identities; this.passwords = passwords; this.tokens = tokens; this.throttle = throttle;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public TokenService.Token login(String username, String password, String address) {
        throttle.check(address);
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        var credentials = identities.credentials(username.strip());
        boolean matches = passwords.matches(password, credentials.map(IdentityRepository.Credentials::hash).orElse(dummyHash));
        if (!matches || credentials.isEmpty() || !credentials.get().active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        var account = credentials.get();
        var user = identities.activeUser(account.id(), account.tokenVersion())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));
        identities.event(user.id(), "LOGIN");
        return tokens.issue(user, account.tokenVersion());
    }
    @Transactional
    public void logout(UUID id) { identities.revoke(id); identities.event(id, "LOGOUT_ALL_SESSIONS"); }
    @Transactional
    public void language(UUID id, String language) { identities.language(id, language); }
}
