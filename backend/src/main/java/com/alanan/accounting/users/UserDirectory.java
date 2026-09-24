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
    public record UserSummary(UUID id, String username, String displayName, boolean active, List<String> roles) {}
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<UserSummary> list(int page) {
        return jdbc.query("""
            SELECT u.id,u.username,u.display_name,u.active,
                   COALESCE(string_agg(r.name,',' ORDER BY r.name),'') role_names
            FROM users u LEFT JOIN user_roles ur ON ur.user_id=u.id LEFT JOIN roles r ON r.id=ur.role_id
            GROUP BY u.id ORDER BY u.username,u.id LIMIT 50 OFFSET ?
            """, (rs, row) -> new UserSummary(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("display_name"), rs.getBoolean("active"),
                rs.getString("role_names").isEmpty() ? List.of() : List.of(rs.getString("role_names").split(","))),
            (long) page * 50);
    }
}
