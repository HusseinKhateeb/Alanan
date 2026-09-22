package com.alanan.accounting.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;
    public AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${app.bootstrap.username:}") String username,
            @Value("${app.bootstrap.password:}") String password) {
        this.jdbc = jdbc; this.encoder = encoder; this.username = username; this.password = password;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Serialize initial provisioning across application instances. Never reset an existing account.
        jdbc.execute("LOCK TABLE users IN EXCLUSIVE MODE");
        if (jdbc.queryForObject("SELECT count(*) FROM users", Long.class) != 0) return;
        if (!username.matches("[A-Za-z0-9._-]{3,100}") || password.length() < 14
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Bootstrap requires a username of 3–100 ASCII letters/digits/._- and a password of at least 14 characters, at most 72 UTF-8 bytes");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, username, display_name, password_hash) VALUES (?, ?, ?, ?)",
            id, username, username, encoder.encode(password));
        jdbc.update("INSERT INTO user_roles(user_id, role_id) SELECT ?, id FROM roles WHERE name = 'ADMIN'", id);
        jdbc.update("INSERT INTO auth_events(user_id, action) VALUES (?, 'ADMIN_BOOTSTRAPPED')", id);
    }
}
