package com.alanan.accounting.operations;

import com.alanan.accounting.auth.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class ExpenseWasteIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl);p.add("spring.datasource.username",db::getUsername);p.add("spring.datasource.password",db::getPassword);
        p.add("app.auth.jwt-secret",()->Base64.getEncoder().encodeToString((PASSWORD+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        p.add("app.bootstrap.enabled",()->true);p.add("app.bootstrap.username",()->"owner");p.add("app.bootstrap.password",()->PASSWORD);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired AdminBootstrap bootstrap; @Autowired IdentityRepository identities; @Autowired TokenService tokens;
    UUID product,category,owner;String token;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,products,expense_categories CASCADE");bootstrap.run(new DefaultApplicationArguments());
        owner=identities.credentials("owner").orElseThrow().id(); token=tokens.issue(identities.activeUser(owner,0).orElseThrow(),0).accessToken();
        product=UUID.randomUUID();category=UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,code,name,category,tracking_type,default_selling_price,current_stock,current_wac) VALUES (?,'S','Spice','SPICE','WEIGHT',45,10,20)",product);
        jdbc.update("INSERT INTO expense_categories(id,name) VALUES (?,'Rent')",category);
    }
    Map<String,Object> expense() { return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"categoryId",category,"amount","125.50","expenseDate","2026-01-01","notes","Monthly rent")); }
    Map<String,Object> waste(String quantity) { return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"productId",product,"quantity",quantity,"wasteDate","2026-01-02","reason","Damaged")); }
    org.springframework.mock.web.MockHttpServletResponse submit(String path,Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/"+path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
    }
    JsonNode save(String path,Map<String,Object> body) throws Exception {
        var result=submit(path,body);assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(201);return json.readTree(result.getContentAsString());
    }
    @Test void expensePreservesSnapshotsAndFiltersWithoutStockChanges() throws Exception {
        var record=save("expenses",expense());
        assertThat(record.get("amount").asText()).isEqualTo("125.50");assertThat(record.get("payment_method_name").asText()).isEqualTo("Cash");
        jdbc.update("UPDATE expense_categories SET name='Changed' WHERE id=?",category);
        mvc.perform(get("/api/expenses/"+record.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("category_name").value("Rent"));
        mvc.perform(get("/api/expenses").param("from","2026-01-01").param("to","2026-01-01").param("categoryId",category.toString()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1));
        mvc.perform(get("/api/expenses").param("from","2026-01-02").header("Authorization","Bearer "+token)).andExpect(jsonPath("items.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense_audit",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isZero();
    }
    @Test void wasteFreezesCostDeductsStockAndAudits() throws Exception {
        var record=save("waste",waste("0.750"));
        assertThat(record.get("cost_total").asText()).isEqualTo("15.00");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("9.250");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT quantity_change FROM inventory_movements",BigDecimal.class)).isEqualByComparingTo("-0.750");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_audit",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE products SET current_wac=99,name='Changed' WHERE id=?",product);
        mvc.perform(get("/api/waste/"+record.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("cost_total").value("15.00")).andExpect(jsonPath("product_name").value("Spice"));
    }
    @Test void unknownCostAndNegativeStockRemainExplicit() throws Exception {
        jdbc.update("UPDATE products SET current_wac=NULL WHERE id=?",product);
        var record=save("waste",waste("11"));assertThat(record.get("cost_total").isNull()).isTrue();assertThat(record.get("negative_stock").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT new_values->>'negativeStock' FROM waste_audit",String.class)).isEqualTo("true");
    }
    @Test void identicalRetriesHaveOneEffectAndChangedRequestsConflict() throws Exception {
        for(String path:List.of("expenses","waste")) {
            var body=path.equals("expenses") ? expense() : waste("1"); var first=save(path,body); assertThat(save(path,body)).isEqualTo(first);
            body.put("notes","Changed");assertThat(submit(path,body).getStatus()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isEqualTo(1);
    }
    @Test void invalidReferencesAndFractionalPiecesHaveNoEffects() throws Exception {
        jdbc.update("UPDATE expense_categories SET active=false WHERE id=?",category);assertThat(submit("expenses",expense()).getStatus()).isEqualTo(400);
        jdbc.update("UPDATE products SET category='HONEY',tracking_type='PIECE' WHERE id=?",product);assertThat(submit("waste",waste("0.5")).getStatus()).isEqualTo(400);
        var body=waste("1");body.put("reason"," ");assertThat(submit("waste",body).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("10");
    }
    @Test void stockOverflowLeavesNoPartialRecords() throws Exception {
        jdbc.update("UPDATE products SET current_stock=-999999999999999.999 WHERE id=?",product);
        assertThat(submit("waste",waste("1")).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_audit",Integer.class)).isZero();
    }
    @Test void concurrentWasteAndSameKeyRetriesDoNotLoseStock() throws Exception {
        var body=waste("1"); concurrent(body,body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_records",Integer.class)).isEqualTo(1);
        concurrent(waste("2"),waste("3"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("4");
    }
    @Test void capabilityBoundariesHideWasteCosts() throws Exception {
        var record=save("waste",waste("1"));
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        for(String path:List.of("expenses","waste")) mvc.perform(get("/api/"+path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertThat(submit("expenses",expense()).getStatus()).isEqualTo(403);assertThat(submit("waste",waste("1")).getStatus()).isEqualTo(403);
        jdbc.update("INSERT INTO roles(name) VALUES ('WASTE_OPERATOR') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO role_permissions SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.name='WASTE_OPERATOR' AND p.code='INVENTORY_ADJUST' ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='WASTE_OPERATOR'",owner);
        mvc.perform(get("/api/waste/"+record.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("cost_total").doesNotExist()).andExpect(jsonPath("cost_at_waste").doesNotExist());
        assertThat(save("waste",waste("1")).has("cost_total")).isFalse();
    }
    @Test void filtersRejectReversedDatesAndProductUnitsAreLockedAfterWaste() throws Exception {
        save("waste",waste("1"));
        mvc.perform(get("/api/waste").param("from","2026-02-01").param("to","2026-01-01").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/waste").param("productId",UUID.randomUUID().toString()).header("Authorization","Bearer "+token)).andExpect(jsonPath("items.length()").value(0));
        mvc.perform(put("/api/products/"+product).header("Authorization","Bearer "+token).contentType("application/json").content("{\"name\":\"Spice\",\"code\":\"S\",\"category\":\"OTHER\",\"trackingType\":\"PIECE\",\"price\":\"45\",\"active\":true,\"version\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("PRODUCT_UNIT_LOCKED"));
    }
    void concurrent(Map<String,Object> a,Map<String,Object> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return save("waste",a);});var second=pool.submit(()->{start.await();return save("waste",b);});
            start.countDown();first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
        }
    }
}
