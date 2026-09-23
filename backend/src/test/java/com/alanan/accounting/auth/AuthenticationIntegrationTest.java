package com.alanan.accounting.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationIntegrationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String SECRET = Base64.getEncoder().encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
        properties.add("app.auth.jwt-secret", () -> SECRET);
        properties.add("app.bootstrap.enabled", () -> true);
        properties.add("app.bootstrap.username", () -> "owner");
        properties.add("app.bootstrap.password", () -> PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JwtEncoder encoder;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users CASCADE");
        bootstrap.run(new DefaultApplicationArguments());
    }
    JsonNode login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr(UUID.randomUUID().toString()); return req; })
            .contentType("application/json").content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString());
    }
    String ownerToken() throws Exception { return login("OWNER", PASSWORD).get("accessToken").asText(); }
    UUID employee() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, username, password_hash, display_name) VALUES (?, 'employee', ?, 'Employee')", id, passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles(user_id, role_id) SELECT ?, id FROM roles WHERE name = 'EMPLOYEE'", id);
        return id;
    }

    JsonNode createReference(String kind, String body, String token) throws Exception {
        return json.readTree(mvc.perform(post("/api/" + kind).header("Authorization", "Bearer " + token)
            .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    @Test void productValidationAndDecimalPrecisionAreEnforced() throws Exception {
        String token = ownerToken();
        var product = createReference("products", "{\"name\":\"Spice\",\"code\":\"S-" + UUID.randomUUID() + "\",\"category\":\"SPICE\",\"trackingType\":\"WEIGHT\",\"price\":\"45.25\",\"active\":true,\"version\":0}", token);
        assertThat(product.get("default_selling_price").asText()).isEqualTo("45.25");
        assertThat(product.has("current_wac")).isFalse();
        mvc.perform(post("/api/products").header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"name\":\"Invalid\",\"code\":\"bad\",\"category\":\"SPICE\",\"trackingType\":\"PIECE\",\"price\":\"4\",\"active\":true,\"version\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("INVALID_TRACKING_TYPE"));
        mvc.perform(post("/api/products").header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"name\":\"Invalid\",\"price\":\"4.123\",\"active\":true,\"version\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("fieldErrors.price").exists());
    }
    @Test void phoneLookupFindsDuplicatesAndWalkInIsProtected() throws Exception {
        String token = ownerToken();
        String phone = "0599" + String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
        createReference("customers", "{\"name\":\"Customer\",\"phone\":\"" + phone + "\",\"active\":true,\"version\":0}", token);
        mvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"name\":\"Duplicate\",\"phone\":\"(" + phone.substring(0,4) + ") " + phone.substring(4) + "\",\"active\":true,\"version\":0}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("CUSTOMER_PHONE_EXISTS"));
        mvc.perform(get("/api/customers").param("phone", phone).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("items[0].name").value("Customer"));
        createReference("customers", "{\"name\":\"Without phone\",\"active\":true,\"version\":0}", token);
        mvc.perform(put("/api/customers/00000000-0000-0000-0000-000000000001").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"Renamed\",\"active\":false,\"version\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("WALK_IN_PROTECTED"));
    }
    @Test void referenceEditsAreVersionedAndAudited() throws Exception {
        String token = ownerToken();
        for (String kind : new String[]{"suppliers", "container-types", "expense-categories"}) {
            String name = "Reference-" + UUID.randomUUID();
            String body = "{\"name\":\"" + name + "\",\"price\":\"1.50\",\"active\":true,\"version\":0}";
            var record = createReference(kind, body, token);
            String path = "/api/" + kind + "/" + record.get("id").asText();
            mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(body.replace("true", "false")))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1)).andExpect(jsonPath("active").value(false));
            mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("EDIT_CONFLICT"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM master_data_audit WHERE entity_id=?", Integer.class, record.get("id").asText())).isEqualTo(2);
        }
    }
    @Test void paymentDefaultRemainsUniqueAndActive() throws Exception {
        String token = ownerToken();
        var method = createReference("payment-methods", "{\"name\":\"Payment-" + UUID.randomUUID() + "\",\"active\":true,\"isDefault\":true,\"version\":0}", token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_methods WHERE is_default AND active", Integer.class)).isEqualTo(1);
        mvc.perform(put("/api/payment-methods/" + method.get("id").asText()).header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"Invalid\",\"active\":false,\"isDefault\":true,\"version\":0}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_methods WHERE is_default AND active", Integer.class)).isEqualTo(1);
        // Restore the seeded default for the existing bootstrap assertion.
        jdbc.update("UPDATE payment_methods SET is_default=false WHERE is_default");
        jdbc.update("UPDATE payment_methods SET is_default=true WHERE name='Cash'");
    }
    @Test void employeeCanReadSalesReferencesButCannotModifyOrReadSuppliers() throws Exception {
        employee(); String token = login("employee", PASSWORD).get("accessToken").asText();
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/suppliers").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType("application/json")
            .content("{\"name\":\"Blocked\",\"active\":true,\"version\":0}"))
            .andExpect(status().isForbidden());
    }
    @Test void migrationsAndBootstrapCreateHashedAdminOnce() throws Exception {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username='owner'", String.class);
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_methods WHERE active AND is_default", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + ownerToken()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("owner"))
            .andExpect(jsonPath("$[0].password_hash").doesNotExist());
    }
    @Test void bootstrapAddsConfiguredAdminWithoutChangingExistingAccounts() throws Exception {
        jdbc.execute("TRUNCATE users CASCADE");
        UUID existing = employee();
        String employeeHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, existing);
        bootstrap.run(new DefaultApplicationArguments());
        String adminHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username='owner'", String.class);
        assertThat(passwords.matches(PASSWORD, adminHash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username='owner' AND id IN (SELECT user_id FROM user_roles WHERE role_id=(SELECT id FROM roles WHERE name='ADMIN'))", Integer.class)).isEqualTo(1);
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, existing)).isEqualTo(employeeHash);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username='owner'", String.class)).isEqualTo(adminHash);
        assertThat(login("owner", PASSWORD).get("accessToken").asText()).isNotBlank();
    }
    @Test void employeesOnlyReceiveTheirCapabilitiesAndCannotListUsers() throws Exception {
        employee();
        var response = login("employee", PASSWORD);
        assertThat(response.get("user").get("permissions").toString()).contains("SALES_CREATE", "SALES_VIEW").doesNotContain("COST_VIEW", "USERS_MANAGE");
        String token = response.get("accessToken").asText();
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
    @Test void authorizationReflectsDatabaseChangesWithoutNewLogin() throws Exception {
        String token = ownerToken();
        jdbc.update("DELETE FROM role_permissions WHERE permission_id=(SELECT id FROM permissions WHERE code='USERS_MANAGE')");
        try {
            mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        } finally {
            jdbc.update("INSERT INTO role_permissions SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.name='ADMIN' AND p.code='USERS_MANAGE'");
        }
    }
    @Test void logoutRevokesExistingTokens() throws Exception {
        String token = ownerToken();
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("errorCode").value("UNAUTHENTICATED"));
        assertThat(ownerToken()).isNotBlank();
    }
    @Test void inactiveAccountsCannotLoginOrReuseToken() throws Exception {
        String token = ownerToken();
        jdbc.update("UPDATE users SET active=false");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "owner", "password", PASSWORD))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("errorCode").value("INVALID_CREDENTIALS"));
    }
    @Test void invalidAndExpiredTokensAreRejected() throws Exception {
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE username='owner'", UUID.class);
        var claims = JwtClaimsSet.builder().issuer("alanan").subject(id.toString()).issuedAt(Instant.now().minusSeconds(600))
            .expiresAt(Instant.now().minusSeconds(300)).claim("ver", 0L).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        for (String token : new String[]{"invalid.token.value", expired}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void invalidCredentialsAndFieldErrorsAreSafeAndStructured() throws Exception {
        for (String username : new String[]{"owner", "missing"}) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", username, "password", "wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("errorCode").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("traceId").isNotEmpty()).andExpect(jsonPath("stackTrace").doesNotExist());
        }
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("fieldErrors.username").value("errors.INVALID_FIELD"));
    }
    @Test void languagePersistsAndOnlySupportedValuesAreAccepted() throws Exception {
        String token = ownerToken();
        mvc.perform(put("/api/auth/language").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"language\":\"en\"}"))
            .andExpect(status().isNoContent());
        assertThat(login("owner", PASSWORD).at("/user/preferredLanguage").asText()).isEqualTo("en");
        mvc.perform(put("/api/auth/language").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"language\":\"xx\"}"))
            .andExpect(status().isBadRequest());
    }
}
