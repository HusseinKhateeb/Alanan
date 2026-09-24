package com.alanan.accounting.auth;

import com.alanan.accounting.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshSessions {
    public static final Duration LIFETIME = Duration.ofDays(7);
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final TokenService tokens;

    public RefreshSessions(JdbcTemplate jdbc, IdentityRepository identities, TokenService tokens) {
        this.jdbc = jdbc; this.identities = identities; this.tokens = tokens;
    }

    public record Issued(TokenService.Token access, String refreshToken) {}

    @Transactional
    public Issued create(IdentityRepository.Credentials account, AuthUser user) {
        String raw = randomToken();
        jdbc.update("INSERT INTO refresh_sessions(id, user_id, token_hash, token_version, expires_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), user.id(), hash(raw), account.tokenVersion(), java.sql.Timestamp.from(Instant.now().plus(LIFETIME)));
        return new Issued(tokens.issue(user, account.tokenVersion()), raw);
    }

    @Transactional
    public Issued rotate(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw unauthenticated();
        var rows = jdbc.query("SELECT id, user_id, token_version, expires_at FROM refresh_sessions WHERE token_hash = ? FOR UPDATE",
            (rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getLong("token_version"), rs.getTimestamp("expires_at").toInstant()), hash(raw));
        if (rows.isEmpty()) throw unauthenticated();
        var session = rows.get(0);
        var user = identities.activeUser(session.userId(), session.version()).orElseThrow(RefreshSessions::unauthenticated);
        if (!session.expiresAt().isAfter(Instant.now())) throw unauthenticated();
        String next = randomToken();
        jdbc.update("UPDATE refresh_sessions SET token_hash = ?, expires_at = ? WHERE id = ?",
            hash(next), java.sql.Timestamp.from(Instant.now().plus(LIFETIME)), session.id());
        return new Issued(tokens.issue(user, session.version()), next);
    }

    @Transactional
    public void revokeAll(UUID userId) { jdbc.update("DELETE FROM refresh_sessions WHERE user_id = ?", userId); }

    private String randomToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static ApiException unauthenticated() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"); }
    private record Session(UUID id, UUID userId, long version, Instant expiresAt) {}
}
