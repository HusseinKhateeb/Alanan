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
import org.springframework.mock.web.MockHttpServletResponse;
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
    @Autowired JwtDecoder decoder;

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
    @Test void signOutCurrentRevokesOnlyThatSession() throws Exception {
        MockHttpServletResponse first = mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "owner", "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn().getResponse();
        String firstToken = json.readTree(first.getContentAsString()).get("accessToken").asText();
        String firstCookie = first.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        String otherToken = ownerToken();
        UUID firstSessionId = UUID.fromString(decoder.decode(firstToken).getClaimAsString("sid"));
        mvc.perform(post("/api/auth/logout-current").header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE id = ?", Integer.class, firstSessionId)).isZero();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + firstToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("alanan_refresh", firstCookie))
            .header("X-Alanan-Session", "refresh")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + otherToken)).andExpect(status().isOk());
    }
    @Test void changingPasswordRevokesExistingTokensAndRequiresTheCurrentPassword() throws Exception {
        String token = ownerToken();
        String nextPassword = "abc123";
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "abc12"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("PASSWORD_POLICY"));
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword", "wrong", "newPassword", nextPassword))))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", nextPassword))))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "owner", "password", PASSWORD))))
            .andExpect(status().isUnauthorized());
        login("owner", nextPassword);
    }
    @Test void refreshCookieRestoresLoginRotatesAndLogoutRevokesIt() throws Exception {
        MockHttpServletResponse login = mvc.perform(post("/api/auth/login")
            .contentType("application/json").content(json.writeValueAsString(Map.of("username", "owner", "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn().getResponse();
        String firstCookie = login.getHeader("Set-Cookie");
        assertThat(firstCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
        assertThat(firstCookie).doesNotContain(PASSWORD);
        String first = firstCookie.split(";", 2)[0];
        String firstAccess = json.readTree(login.getContentAsString()).get("accessToken").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE token_hash = ?", Integer.class, first.split("=", 2)[1])).isZero();
        mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("alanan_refresh", first.split("=", 2)[1])))
            .andExpect(status().isForbidden());
        MockHttpServletResponse refreshed = mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("alanan_refresh", first.split("=", 2)[1]))
            .header("X-Alanan-Session", "refresh"))
            .andExpect(status().isOk()).andExpect(jsonPath("user.username").value("owner"))
            .andReturn().getResponse();
        String second = refreshed.getHeader("Set-Cookie").split(";", 2)[0];
        assertThat(second).isNotEqualTo(first);
        assertThat(json.readTree(refreshed.getContentAsString()).get("accessToken").asText()).isNotEqualTo(firstAccess);
        mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("alanan_refresh", first.split("=", 2)[1])).header("X-Alanan-Session", "refresh"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + firstAccess).cookie(new jakarta.servlet.http.Cookie("alanan_refresh", second.split("=", 2)[1])))
            .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("alanan_refresh", second.split("=", 2)[1])).header("X-Alanan-Session", "refresh"))
            .andExpect(status().isUnauthorized());
    }
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
    @Test void productCodesGenerateWhenOmittedAndStayStableOnEdit() throws Exception {
        String token = ownerToken();
        String body = "{\"name\":\"Generated product\",\"category\":\"SPICE\",\"trackingType\":\"WEIGHT\",\"price\":\"12.00\",\"active\":true,\"version\":0}";
        var first = createReference("products", body, token);
        var second = createReference("products", body.replace("Generated product", "Another product"), token);
        String code = first.get("code").asText();
        assertThat(code).matches("PRD-[0-9]{6,}");
        assertThat(second.get("code").asText()).isNotEqualTo(code);
        mvc.perform(put("/api/products/" + first.get("id").asText()).header("Authorization", "Bearer " + token)
            .contentType("application/json").content(body.replace("Generated product", "Renamed product")))
            .andExpect(status().isOk()).andExpect(jsonPath("code").value(code));
    }
    @Test void productSearchMatchesLaterWordsAndSubstrings() throws Exception {
        String token = ownerToken();
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var product = createReference("products", "{\"name\":\"Roasted Cardamom" + marker + " Blend\",\"code\":\"SKU-" + marker + "\",\"category\":\"SPICE\",\"trackingType\":\"WEIGHT\",\"price\":\"12.00\",\"active\":true,\"version\":0}", token);
        for (String term : new String[]{"cardamom" + marker.substring(0, 4), marker.substring(4, 9), "sku-" + marker.substring(0, 4)}) {
            mvc.perform(get("/api/products").param("q", term).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].id").value(product.get("id").asText()));
        }
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
    @Test void customerDetailSupportsPaymentPrefillForAuthorizedUsers() throws Exception {
        String owner=ownerToken();
        var customer=createReference("customers","{\"name\":\"Payment customer\",\"active\":true,\"version\":0}",owner);
        employee(); String clerk=login("employee",PASSWORD).get("accessToken").asText();
        mvc.perform(get("/api/customers/"+customer.get("id").asText()).header("Authorization","Bearer "+clerk))
            .andExpect(status().isOk()).andExpect(jsonPath("name").value("Payment customer"));
        mvc.perform(get("/api/customers/"+UUID.randomUUID()).header("Authorization","Bearer "+clerk))
            .andExpect(status().isNotFound());
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
    @Test void salesEmployeeCanQuickCreateCustomerWithOptionalPhoneButCannotEditRecords() throws Exception {
        employee(); String token = login("employee", PASSWORD).get("accessToken").asText();
        mvc.perform(post("/api/customers/quick").contentType("application/json").content("{\"name\":\"Anonymous\"}"))
            .andExpect(status().isUnauthorized());
        var created = json.readTree(mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"  New customer  \",\"phone\":\"0599123456\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("name").value("New customer"))
            .andExpect(jsonPath("phone").value("0599123456")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"Duplicate\",\"phone\":\"0599 123456\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("CUSTOMER_PHONE_EXISTS"));
        mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"No phone\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("phone").isEmpty());
        mvc.perform(put("/api/customers/" + created.get("id").asText()).header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"Changed\",\"active\":true,\"version\":0}"))
            .andExpect(status().isForbidden());
    }
    @Test void quickCustomerRetryReturnsSameRecordAfterLostResponse() throws Exception {
        employee(); String token = login("employee", PASSWORD).get("accessToken").asText();
        String requestId = java.util.UUID.randomUUID().toString();
        String body = "{\"name\":\"Offline customer\",\"phone\":\"0599876543\",\"requestId\":\"" + requestId + "\"}";
        var first = json.readTree(mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(body)).andExpect(status().isCreated())
            .andExpect(jsonPath("id").value(first.get("id").asText()));
        mvc.perform(post("/api/customers/quick").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"name\":\"Changed customer\",\"requestId\":\"" + requestId + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE name='Offline customer'",Integer.class)).isEqualTo(1);
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
    @Test void adminCreatesUserWithRoleAndIndividualPermissionOverrides() throws Exception {
        String owner = ownerToken();
        mvc.perform(get("/api/users/options").header("Authorization", "Bearer " + owner))
            .andExpect(status().isOk()).andExpect(jsonPath("roles[0].name").exists());
        String body = """
            {"username":"clerk","displayName":"Sales Clerk","password":"correct-horse-battery","preferredLanguage":"en",
             "active":true,"roles":["EMPLOYEE"],"permissions":["SALES_CREATE","COST_VIEW"]}
            """;
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + owner).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("username").value("clerk"))
            .andExpect(jsonPath("password").doesNotExist());
        var clerk = login("clerk", "correct-horse-battery");
        assertThat(clerk.get("user").get("permissions").toString()).contains("SALES_CREATE", "COST_VIEW").doesNotContain("SALES_VIEW", "USERS_MANAGE");
        assertThat(passwords.matches("correct-horse-battery", jdbc.queryForObject("SELECT password_hash FROM users WHERE username='clerk'", String.class))).isTrue();
        assertThat(jdbc.queryForObject("SELECT new_values::text FROM user_audit WHERE action='CREATE' AND target_user_id=(SELECT id FROM users WHERE username='clerk')", String.class))
            .doesNotContain("correct-horse-battery", "password_hash");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + owner).contentType("application/json").content(body.replace("clerk", "CLERK")))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("USERNAME_EXISTS"));
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + clerk.get("accessToken").asText()).contentType("application/json").content(body.replace("clerk", "another")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/options").header("Authorization", "Bearer " + clerk.get("accessToken").asText()))
            .andExpect(status().isForbidden());
    }
    @Test void sixCharacterPasswordCanBeUsedForANewAccountButFiveCannot() throws Exception {
        String owner = ownerToken();
        String body = """
            {"username":"shortpass","displayName":"Short Password","password":"abc123","preferredLanguage":"en",
             "active":true,"roles":["EMPLOYEE"],"permissions":[]}
            """;
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + owner).contentType("application/json")
            .content(body.replace("abc123", "abc12")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("PASSWORD_POLICY"));
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + owner).contentType("application/json")
            .content(body)).andExpect(status().isCreated());
        login("shortpass", "abc123");
    }
    @Test void adminCanChangeAccessAndRemoveUserWithoutErasingHistory() throws Exception {
        String owner = ownerToken();
        String body = """
            {"username":"temporary","displayName":"Temporary","password":"abc123","preferredLanguage":"en",
             "active":true,"roles":["EMPLOYEE"],"permissions":["SALES_CREATE"]}
            """;
        JsonNode created = json.readTree(mvc.perform(post("/api/users").header("Authorization", "Bearer " + owner)
            .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = created.get("id").asText();
        String oldToken = login("temporary", "abc123").get("accessToken").asText();
        mvc.perform(get("/api/users/" + id).header("Authorization", "Bearer " + owner))
            .andExpect(status().isOk()).andExpect(jsonPath("permissions[0]").value("SALES_CREATE"));
        mvc.perform(put("/api/users/" + id + "/access").header("Authorization", "Bearer " + oldToken)
            .contentType("application/json").content("{\"roles\":[\"ADMIN\"],\"permissions\":[\"USERS_MANAGE\"]}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/" + id + "/access").header("Authorization", "Bearer " + owner)
            .contentType("application/json").content("{\"roles\":[\"EMPLOYEE\"],\"permissions\":[\"SALES_VIEW\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("permissions[0]").value("SALES_VIEW"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        assertThat(login("temporary", "abc123").get("user").get("permissions").toString())
            .contains("SALES_VIEW").doesNotContain("SALES_CREATE");
        mvc.perform(delete("/api/users/" + id).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "temporary", "password", "abc123"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/" + id).header("Authorization", "Bearer " + owner)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?::uuid AND deleted_at IS NOT NULL", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_events WHERE user_id=?::uuid", Integer.class, id)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_audit WHERE target_user_id=?::uuid AND action='REMOVE'", Integer.class, id)).isEqualTo(1);
        UUID ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username='owner'", UUID.class);
        mvc.perform(delete("/api/users/" + ownerId).header("Authorization", "Bearer " + owner))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("CANNOT_REMOVE_SELF"));
    }
    @Test void manageUsersPermissionWithoutAdminRoleCannotCreateAccounts() throws Exception {
        UUID id = employee();
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,true FROM permissions WHERE code='USERS_MANAGE'", id);
        var login = login("employee", PASSWORD);
        assertThat(login.get("user").get("permissions").toString()).contains("USERS_MANAGE");
        assertThat(login.get("user").get("roles").toString()).contains("EMPLOYEE").doesNotContain("ADMIN");
        String token = login.get("accessToken").asText();
        String body = """
            {"username":"blocked","displayName":"Blocked","password":"correct-horse-battery","preferredLanguage":"en",
             "active":true,"roles":["EMPLOYEE"],"permissions":[]}
            """;
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/users/options").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username='blocked'", Integer.class)).isZero();
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
