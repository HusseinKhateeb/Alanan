package com.alanan.accounting.auth;

import com.alanan.accounting.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
//v1
@Service
public class AuthService {
    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final LoginThrottle throttle;
    private final RefreshSessions refreshSessions;
    private final JdbcTemplate jdbc;
    private final String dummyHash;
    public AuthService(IdentityRepository identities, PasswordEncoder passwords, LoginThrottle throttle, RefreshSessions refreshSessions, JdbcTemplate jdbc) {
        this.identities = identities; this.passwords = passwords; this.throttle = throttle; this.refreshSessions = refreshSessions; this.jdbc = jdbc;
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

    @Transactional
    public void changePassword(UUID id, String currentPassword, String newPassword) {
        if (newPassword.length() < 6 || newPassword.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY");
        var hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE id=? AND active FOR UPDATE", String.class, id);
        if (hashes.isEmpty() || !passwords.matches(currentPassword, hashes.getFirst()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIALS");
        if (currentPassword.equals(newPassword)) throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED");
        jdbc.update("UPDATE users SET password_hash=?, token_version=token_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?",
            passwords.encode(newPassword), id);
        refreshSessions.revokeAll(id);
        identities.event(id, "PASSWORD_CHANGED");
    }
}
