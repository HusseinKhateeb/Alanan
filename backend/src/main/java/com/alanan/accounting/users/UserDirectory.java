package com.alanan.accounting.users;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class UserDirectory {
    private final JdbcTemplate jdbc;
    public UserDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record UserSummary(UUID id, String username, String displayName, boolean active) {}
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<UserSummary> list(int page) {
        return jdbc.query("SELECT id, username, display_name, active FROM users ORDER BY username, id LIMIT 50 OFFSET ?",
            (rs, row) -> new UserSummary(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("display_name"), rs.getBoolean("active")), (long) page * 50);
    }
}
