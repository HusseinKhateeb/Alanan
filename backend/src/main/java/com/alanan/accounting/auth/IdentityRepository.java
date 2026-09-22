package com.alanan.accounting.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;
    public IdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Credentials(UUID id, String hash, boolean active, long tokenVersion) {}

    public Optional<Credentials> credentials(String username) {
        return jdbc.query("SELECT id, password_hash, active, token_version FROM users WHERE lower(username) = lower(?)",
            (rs, row) -> new Credentials(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                rs.getBoolean("active"), rs.getLong("token_version")), username).stream().findFirst();
    }

    public Optional<AuthUser> activeUser(UUID id, long tokenVersion) {
        return jdbc.query("SELECT id, username, display_name, preferred_language FROM users WHERE id = ? AND active AND token_version = ?",
            (rs, row) -> new AuthUser(id, rs.getString("username"), rs.getString("display_name"),
                rs.getString("preferred_language"), permissions(id)), id, tokenVersion).stream().findFirst();
    }

    private List<String> permissions(UUID id) {
        return jdbc.queryForList("""
            SELECT DISTINCT p.code FROM permissions p
            JOIN role_permissions rp ON rp.permission_id = p.id
            JOIN user_roles ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = ? ORDER BY p.code
            """, String.class, id);
    }

    public void event(UUID userId, String action) {
        jdbc.update("INSERT INTO auth_events(user_id, action) VALUES (?, ?)", userId, action);
    }

    public void revoke(UUID id) {
        jdbc.update("UPDATE users SET token_version = token_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void language(UUID id, String language) {
        jdbc.update("UPDATE users SET preferred_language = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", language, id);
    }
}
