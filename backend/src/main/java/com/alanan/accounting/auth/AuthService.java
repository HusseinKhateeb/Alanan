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
    private final LoginThrottle throttle;
    private final RefreshSessions refreshSessions;
    private final String dummyHash;
    public AuthService(IdentityRepository identities, PasswordEncoder passwords, LoginThrottle throttle, RefreshSessions refreshSessions) {
        this.identities = identities; this.passwords = passwords; this.throttle = throttle; this.refreshSessions = refreshSessions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public RefreshSessions.Issued login(String username, String password, String address) {
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
        return refreshSessions.create(account, user);
    }
    @Transactional
    public void logout(UUID id) { identities.revoke(id); refreshSessions.revokeAll(id); identities.event(id, "LOGOUT_ALL_SESSIONS"); }
    @Transactional
    public void language(UUID id, String language) { identities.language(id, language); }
}
