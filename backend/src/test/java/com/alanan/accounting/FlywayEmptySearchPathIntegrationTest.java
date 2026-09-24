package com.alanan.accounting;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @Testcontainers
class FlywayEmptySearchPathIntegrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17.9-alpine");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = db.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "options=-c%20search_path%3D";
        });
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("app.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(
            "flyway-empty-search-path-integration-test-secret".getBytes(StandardCharsets.UTF_8)));
        registry.add("app.bootstrap.enabled", () -> true);
        registry.add("app.bootstrap.username", () -> "owner");
        registry.add("app.bootstrap.password", () -> "isolated-test-password-123");
    }

    @Autowired JdbcTemplate jdbc;

    @Test void applicationQueriesSeePublicSchemaDespiteEmptyServerSearchPath() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("public");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username='owner'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class))
            .isPositive();
    }
}
