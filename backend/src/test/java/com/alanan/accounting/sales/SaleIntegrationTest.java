package com.alanan.accounting.sales;

import com.alanan.accounting.auth.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
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
class SaleIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl); p.add("spring.datasource.username",db::getUsername); p.add("spring.datasource.password",db::getPassword);
        p.add("app.auth.jwt-secret",()->Base64.getEncoder().encodeToString((PASSWORD+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        p.add("app.bootstrap.enabled",()->true); p.add("app.bootstrap.username",()->"owner"); p.add("app.bootstrap.password",()->PASSWORD);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired AdminBootstrap bootstrap; @Autowired IdentityRepository identities; @Autowired TokenService tokens;
    UUID spice,honey,container,customer; String token;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,products,container_types,invoice_counters CASCADE");
        jdbc.update("DELETE FROM customers WHERE NOT is_walk_in");
        bootstrap.run(new DefaultApplicationArguments());
        var account=identities.credentials("owner").orElseThrow();
        token=tokens.issue(identities.activeUser(account.id(),0).orElseThrow(),0).accessToken();
        spice=UUID.randomUUID(); honey=UUID.randomUUID(); container=UUID.randomUUID(); customer=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,name) VALUES (?,'Customer')",customer);
        jdbc.update("INSERT INTO products(id,code,name,category,tracking_type,default_selling_price,current_stock,current_wac) VALUES (?,'S','Spice','SPICE','WEIGHT',45,10,20),(?,'H','Honey','HONEY','PIECE',80,4,40)",spice,honey);
        jdbc.update("INSERT INTO container_types(id,name,default_price) VALUES (?,'Small',1.5)",container);
    }
    Map<String,Object> item(UUID id,String mode,String value,String price) {
        return Map.of("productId",id,"mode",mode,mode.equals("BY_AMOUNT") ? "requestedAmount" : "quantity",value,"unitPrice",price);
    }
    Map<String,Object> request(String paid,List<Map<String,Object>> items) {
        var body=new LinkedHashMap<String,Object>(); body.put("requestId",UUID.randomUUID()); body.put("customerId",customer);
        body.put("invoiceDate","2026-01-01"); body.put("paidNow",paid); body.put("items",items); return body;
    }
    JsonNode create(Map<String,Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    void reject(Map<String,Object> body,String code) throws Exception {
        mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value(code));
    }
    @Test void mixedSaleFreezesCostsAndNamesAndUpdatesBalances() throws Exception {
        var result=create(request("100",List.of(item(spice,"BY_WEIGHT","1.250","45"),item(honey,"BY_PIECE","2","80"),Map.of("containerTypeId",container,"mode","BY_PIECE","quantity","20","unitPrice","1.50"))));
        assertThat(result.get("total").asText()).isEqualTo("246.25");
        assertThat(result.get("outstanding").asText()).isEqualTo("146.25");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,spice)).isEqualByComparingTo("8.750");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_audit",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE products SET name='Renamed',current_wac=99 WHERE id=?",spice);
        mvc.perform(get("/api/sales/"+result.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("items[0].item_name").value("Spice")).andExpect(jsonPath("items[0].cost_at_sale").value("20.000000"))
            .andExpect(jsonPath("items[0].cost_total").value("25.00")).andExpect(jsonPath("items[2].product_id").isEmpty()).andExpect(jsonPath("items[2].cost_total").isEmpty());
        mvc.perform(get("/api/customer-balances").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].outstanding").value("146.25"));
    }
    @Test void byAmountRoundsQuantityButPreservesRequestedRevenue() throws Exception {
        create(request("20",List.of(item(spice,"BY_AMOUNT","20","45"))));
        assertThat(jdbc.queryForObject("SELECT quantity FROM sale_items",BigDecimal.class)).isEqualByComparingTo("0.444");
        assertThat(jdbc.queryForObject("SELECT line_total FROM sale_items",BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT default_selling_price FROM products WHERE id=?",BigDecimal.class,spice)).isEqualByComparingTo("45");
    }
    @Test void anonymousContainerHasNoProductOrInventoryEffects() throws Exception {
        create(request("60",List.of(Map.of("containerTypeId",container,"mode","BY_PIECE","quantity","40","unitPrice","1.50"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isZero();
        reject(request("60",List.of(Map.of("containerTypeId",container,"productId",spice,"mode","BY_PIECE","quantity","40","unitPrice","1.50"))),"SALE_REFERENCE_REQUIRED");
    }
    @Test void negativeStockAndUnknownCostAreExplicitAndAudited() throws Exception {
        jdbc.update("UPDATE products SET current_wac=NULL WHERE id=?",spice);
        var result=create(request("0",List.of(item(spice,"BY_WEIGHT","11","45"))));
        assertThat(result.get("negativeStock").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,spice)).isEqualByComparingTo("-1");
        assertThat(jdbc.queryForObject("SELECT cost_total FROM sale_items",BigDecimal.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT new_values->>'negativeStock' FROM sale_audit",String.class)).isEqualTo("true");
    }
    @Test void malformedModesTinyQuantitiesAndOverpaymentsHaveNoEffects() throws Exception {
        reject(request("46",List.of(item(spice,"BY_WEIGHT","1","45"))),"PAYMENT_EXCEEDS_TOTAL");
        reject(request("0",List.of(item(honey,"BY_PIECE","1.5","80"))),"WHOLE_PIECES_REQUIRED");
        reject(request("0",List.of(item(honey,"BY_WEIGHT","1","80"))),"SALE_MODE_INVALID");
        reject(request("0",List.of(item(spice,"BY_AMOUNT","0.01","999"))),"SALE_AMOUNT_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isZero();
    }
    @Test void walkInDefaultsAndMustBeFullyPaid() throws Exception {
        var body=request("0",List.of(item(spice,"BY_WEIGHT","1","45"))); body.remove("customerId");
        reject(body,"WALK_IN_MUST_PAY_FULL"); body.put("paidNow","45"); create(body);
        assertThat(jdbc.queryForObject("SELECT customer_name FROM sales",String.class)).isEqualTo("Walk-in Customer");
    }
    @Test void sameKeyReplaysAndChangedPayloadConflicts() throws Exception {
        var body=request("0",List.of(item(spice,"BY_WEIGHT","1","45"))); var first=create(body); assertThat(create(body)).isEqualTo(first);
        body.put("paidNow","1");
        mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentSalesAndRetriesDoNotLoseStockOrDuplicateInvoices() throws Exception {
        var body=request("0",List.of(item(spice,"BY_WEIGHT","1","45")));
        concurrent(body,body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales",Integer.class)).isEqualTo(1);
        concurrent(request("0",List.of(item(spice,"BY_WEIGHT","2","45"))),request("0",List.of(item(spice,"BY_WEIGHT","3","45"))));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,spice)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT invoice_number) FROM sales",Integer.class)).isEqualTo(3);
    }
    @Test void employeesCanSellButCannotReadCostsAndRevokedPermissionsBlockAccess() throws Exception {
        var owner=identities.credentials("owner").orElseThrow();
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner.id()); jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner.id());
        var result=create(request("45",List.of(item(spice,"BY_WEIGHT","1","45"))));
        mvc.perform(get("/api/sales/"+result.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].cost_at_sale").doesNotExist()).andExpect(jsonPath("items[0].cost_total").doesNotExist());
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner.id());
        mvc.perform(get("/api/sales").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(request("45",List.of(item(spice,"BY_WEIGHT","1","45")))))).andExpect(status().isForbidden());
    }
    @Test void lateStockOverflowRollsBackEarlierProductUpdate() throws Exception {
        var ids=new ArrayList<>(List.of(spice,honey)); Collections.sort(ids);
        jdbc.update("UPDATE products SET category='OTHER',tracking_type='PIECE',current_stock=10");
        jdbc.update("UPDATE products SET current_stock=-999999999999999.999 WHERE id=?",ids.get(1));
        reject(request("0",List.of(item(ids.get(0),"BY_PIECE","1","1"),item(ids.get(1),"BY_PIECE","1","1"))),"SALE_AMOUNT_INVALID");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,ids.get(0))).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isZero();
    }
    @Test void productUnitCannotChangeAfterSaleOnlyHistory() throws Exception {
        create(request("45",List.of(item(spice,"BY_WEIGHT","1","45"))));
        mvc.perform(put("/api/products/"+spice).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"name\":\"Spice\",\"code\":\"S\",\"category\":\"OTHER\",\"trackingType\":\"PIECE\",\"price\":\"45\",\"active\":true,\"version\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("PRODUCT_UNIT_LOCKED"));
    }
    void concurrent(Map<String,Object> first,Map<String,Object> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await(); return create(first);}); var b=pool.submit(()->{start.await(); return create(second);});
            start.countDown(); a.get(30,TimeUnit.SECONDS); b.get(30,TimeUnit.SECONDS);
        }
    }
}
