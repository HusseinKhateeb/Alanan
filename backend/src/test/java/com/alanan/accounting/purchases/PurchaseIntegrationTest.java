package com.alanan.accounting.purchases;

import com.alanan.accounting.auth.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PurchaseIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl); p.add("spring.datasource.username",db::getUsername); p.add("spring.datasource.password",db::getPassword);
        p.add("app.auth.jwt-secret",()->Base64.getEncoder().encodeToString((PASSWORD+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        p.add("app.bootstrap.enabled",()->true); p.add("app.bootstrap.username",()->"owner"); p.add("app.bootstrap.password",()->PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AdminBootstrap bootstrap;
    @Autowired IdentityRepository identities;
    @Autowired AuthService auth;
    UUID supplier, spice, honey;
    String token;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,products,suppliers,invoice_counters CASCADE");
        bootstrap.run(new DefaultApplicationArguments());
        var account=identities.credentials("owner").orElseThrow();
        token=auth.login("owner", PASSWORD, UUID.randomUUID().toString()).access().accessToken();
        supplier=UUID.randomUUID(); spice=UUID.randomUUID(); honey=UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,'Supplier')",supplier);
        jdbc.update("INSERT INTO products(id,code,name,category,tracking_type,default_selling_price) VALUES (?,'S','Spice','SPICE','WEIGHT',45),(?,'H','Honey','HONEY','PIECE',80)",spice,honey);
    }
    Map<String,Object> item(UUID product,String quantity,String cost) { return Map.of("productId",product,"quantity",quantity,"unitCost",cost); }
    Map<String,Object> request(String paid,List<Map<String,Object>> items) {
        var body=new LinkedHashMap<String,Object>();
        body.put("requestId",UUID.randomUUID()); body.put("supplierId",supplier); body.put("invoiceDate","2026-01-01");
        body.put("paidNow",paid); body.put("items",items); return body;
    }
    JsonNode create(Map<String,Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    @Test void adminHardDeletionRestoresPurchaseCostButRejectsLaterMovements() throws Exception {
        var purchase=create(request("0",List.of(item(spice,"2","20"))));
        String path="/api/admin/deletions/purchases/"+purchase.get("id").asText();
        jdbc.update("INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,created_by) VALUES (?,?,'CORRECTION',1,'CORRECTION',?,?)",
            UUID.randomUUID(),spice,UUID.randomUUID(),identities.credentials("owner").orElseThrow().id());
        mvc.perform(delete(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"reason\":\"Wrong invoice\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("DELETE_DEPENDENCIES"));
        jdbc.update("DELETE FROM inventory_movements WHERE reference_type='CORRECTION'");
        mvc.perform(delete(path).header("Authorization","Bearer "+token).contentType("application/json").content("{\"reason\":\"Wrong invoice\"}"))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_invoices",Integer.class)).isZero();
    }
    @Test void adminCanHardDeletePurchaseWithoutNormalCorrectionPermissions() throws Exception {
        var purchase=create(request("0",List.of(item(spice,"1","20"))));
        UUID owner=identities.credentials("owner").orElseThrow().id();
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,FALSE FROM permissions WHERE code IN ('PURCHASES_CREATE','AUDIT_VIEW')",owner);
        mvc.perform(delete("/api/admin/deletions/purchases/"+purchase.get("id").asText())
            .header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Wrong invoice\"}")) .andExpect(status().isNoContent());
    }
    @Test void mixedPurchaseUpdatesStockCostPaymentBalanceAndAudit() throws Exception {
        var result=create(request("100",List.of(item(spice,"10","20"),item(honey,"2","40"))));
        assertThat(result.get("total").asText()).isEqualTo("280.00");
        assertThat(result.get("outstanding").asText()).isEqualTo("180.00");
        assertThat(result.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supplier_payments WHERE payment_method_name='Cash'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_audit",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/supplier-balances").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].outstanding").value("180.00"));
        jdbc.update("UPDATE products SET name='Renamed' WHERE id=?",spice);
        mvc.perform(get("/api/purchases/"+result.get("id").asText()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items[0].product_name").value("Spice"));
    }
    @Test void openingStockAddsOldGoodsWithCostAuditAndIdempotentRetry() throws Exception {
        var input=Map.of("requestId",UUID.randomUUID(),"productId",spice,"quantity","10","unitCost","20",
            "recordedDate","2026-01-01","reason","Goods held before system launch");
        String body=json.writeValueAsString(input);
        var created=json.readTree(mvc.perform(post("/api/inventory/adjustments").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body)).andExpect(status().isCreated())
            .andExpect(jsonPath("new_stock").value("10.000")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/inventory/adjustments").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body)).andExpect(status().isCreated())
            .andExpect(jsonPath("id").value(created.get("id").asText()));
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE movement_type='OPENING'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_adjustment_audit",Integer.class)).isEqualTo(1);
        create(request("0",List.of(item(spice,"10","30"))));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("25");
        mvc.perform(get("/api/inventory/movements").param("productId",spice.toString()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items[1].movement_type").value("OPENING"));
    }
    @Test void openingStockHardDeletionRestoresPreviousValuation() throws Exception {
        var opening=Map.of("requestId",UUID.randomUUID(),"productId",spice,"quantity","4","unitCost","12",
            "recordedDate","2026-01-01","reason","Old stock");
        var response=json.readTree(mvc.perform(post("/api/inventory/adjustments").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json.writeValueAsString(opening))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/admin/deletions/opening-stock/"+response.get("id").asText())
            .header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Entered twice\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments",Integer.class)).isZero();
    }
    @Test void invalidOpeningStockDoesNotChangeInventory() throws Exception {
        var fractional=Map.of("requestId",UUID.randomUUID(),"productId",honey,"quantity","1.5","unitCost","20",
            "recordedDate","2026-01-01","reason","Old goods");
        mvc.perform(post("/api/inventory/adjustments").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json.writeValueAsString(fractional)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("WHOLE_PIECES_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,honey)).isEqualByComparingTo("0");
    }
    @Test void sequentialPurchasesUseWeightedAverageAndDoNotChangeSellingPrice() throws Exception {
        create(request("0",List.of(item(spice,"10","20"))));
        create(request("520",List.of(item(spice,"20","26"))));
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("24");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT default_selling_price FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("45");
    }
    @Test void correctingLatestPurchaseAtomicallyRevaluesStockAndPreservesPaymentsAndAudit() throws Exception {
        var first=create(request("0",List.of(item(spice,"10","20"))));
        var second=create(request("20",List.of(item(spice,"2","30"))));
        String id=second.get("id").asText();
        var correction=Map.of("version",0,"invoiceDate","2026-01-02","reason","Supplier corrected quantity and cost",
            "items",List.of(Map.of("quantity","4","unitCost","25")));
        var wrongYear=Map.of("version",0,"invoiceDate","2025-12-31","reason","Wrong year",
            "items",List.of(Map.of("quantity","4","unitCost","25")));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(wrongYear))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("errorCode").value("INVOICE_YEAR_LOCKED"));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(correction))).andExpect(status().isOk()).andExpect(jsonPath("total").value("100.00"))
            .andExpect(jsonPath("paid").value("20.00"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("14");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("21.428571");
        assertThat(jdbc.queryForObject("SELECT sum(quantity_change) FROM inventory_movements WHERE product_id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("14");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_audit WHERE purchase_invoice_id=? AND action='EDIT'",Integer.class,UUID.fromString(id))).isEqualTo(1);
        mvc.perform(get("/api/purchases/"+id).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("version").value(1)).andExpect(jsonPath("correction_allowed").value(true));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(correction))).andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("EDIT_CONFLICT"));
        mvc.perform(get("/api/purchases/"+first.get("id").asText()).header("Authorization","Bearer "+token))
            .andExpect(jsonPath("correction_allowed").value(true));
        mvc.perform(post("/api/purchases/"+id+"/cancel").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Supplier return\",\"refundReceivedConfirmed\":true}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("20");
    }
    @Test void correctionAfterLaterMovementOrBelowPaymentIsRejectedWithoutPartialEffects() throws Exception {
        var first=create(request("15",List.of(item(spice,"2","10"))));
        String id=first.get("id").asText();
        var underpaid=Map.of("version",0,"invoiceDate","2026-01-01","reason","Cost correction",
            "items",List.of(Map.of("quantity","1","unitCost","10")));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(underpaid))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("errorCode").value("PAYMENT_EXCEEDS_TOTAL"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("2");
        create(request("0",List.of(item(spice,"1","30"))));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(underpaid))).andExpect(status().isConflict())
            .andExpect(jsonPath("errorCode").value("PURCHASE_REVALUATION_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_audit WHERE purchase_invoice_id=? AND action='EDIT'",Integer.class,UUID.fromString(id))).isZero();
    }
    @Test void metadataOnlyCorrectionOfOlderPurchasePreservesStockAndAllowsLaterCostCorrection() throws Exception {
        var first=create(request("0",List.of(item(spice,"2","10"))));
        var second=create(request("0",List.of(item(spice,"1","30"))));
        String firstId=first.get("id").asText(), secondId=second.get("id").asText();
        var metadata=Map.of("version",0,"invoiceDate","2026-01-03","supplierReference","Updated reference",
            "notes","Corrected notes","reason","Corrected supplier paperwork",
            "items",List.of(Map.of("quantity","2","unitCost","10")));
        mvc.perform(put("/api/purchases/"+firstId).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(metadata))).andExpect(status().isOk()).andExpect(jsonPath("total").value("20.00"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("16.666667");
        mvc.perform(get("/api/purchases/"+firstId).header("Authorization","Bearer "+token))
            .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("supplier_reference").value("Updated reference"));
        var costChange=Map.of("version",1,"invoiceDate","2026-01-03","reason","Cost change",
            "items",List.of(Map.of("quantity","2","unitCost","12")));
        mvc.perform(put("/api/purchases/"+firstId).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(costChange))).andExpect(status().isConflict())
            .andExpect(jsonPath("errorCode").value("PURCHASE_REVALUATION_REQUIRED"));
        var latestMetadata=Map.of("version",0,"invoiceDate","2026-01-02","reason","Updated latest reference",
            "supplierReference","Latest paperwork","items",List.of(Map.of("quantity","1","unitCost","30")));
        mvc.perform(put("/api/purchases/"+secondId).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(latestMetadata))).andExpect(status().isOk());
        var latest=Map.of("version",1,"invoiceDate","2026-01-02","reason","Latest cost correction",
            "items",List.of(Map.of("quantity","1","unitCost","25")));
        mvc.perform(put("/api/purchases/"+secondId).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(latest))).andExpect(status().isOk());
    }
    @Test void cancellationAfterMetadataOnlyCorrectionReversesOriginalStockEffects() throws Exception {
        var purchase=create(request("0",List.of(item(spice,"2","10"))));
        String id=purchase.get("id").asText();
        var metadata=Map.of("version",0,"invoiceDate","2026-01-02","reason","Updated paperwork",
            "items",List.of(Map.of("quantity","2","unitCost","10")));
        mvc.perform(put("/api/purchases/"+id).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(metadata))).andExpect(status().isOk());
        mvc.perform(post("/api/purchases/"+id+"/cancel").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Incorrect invoice\",\"refundReceivedConfirmed\":true}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isNull();
    }
    @Test void retriesAreIdempotentAndChangedPayloadConflicts() throws Exception {
        var body=request("10",List.of(item(spice,"2","10")));
        var first=create(body); var second=create(body);
        assertThat(second).isEqualTo(first);
        body.put("paidNow","0");
        mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_invoices",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supplier_payments",Integer.class)).isEqualTo(1);
    }
    @Test void invalidPaymentAndFractionalPiecesHaveNoEffects() throws Exception {
        for(var body: List.of(request("21",List.of(item(spice,"2","10"))),request("0",List.of(item(honey,"1.5","10"))))) {
            mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_invoices",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(current_stock) FROM products",java.math.BigDecimal.class)).isEqualByComparingTo("0");
    }
    @Test void laterProductFailureRollsBackEarlierStockUpdate() throws Exception {
        var ids=new ArrayList<>(List.of(spice,honey)); Collections.sort(ids);
        jdbc.update("UPDATE products SET current_stock=-1 WHERE id=?",ids.get(1));
        var body=request("0",List.of(item(ids.get(0),"2","10"),item(ids.get(1),"2","10")));
        mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("WAC_REVIEW_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,ids.get(0))).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_invoices",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_audit",Integer.class)).isZero();
    }
    @Test void concurrentPurchasesDoNotLoseStockOrDuplicateNumbers() throws Exception {
        runConcurrently(request("0",List.of(item(spice,"10","20"))),request("0",List.of(item(spice,"20","26"))));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("24");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT invoice_number) FROM purchase_invoices",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentRetriesApplyEffectsOnce() throws Exception {
        var body=request("5",List.of(item(spice,"1","10")));
        runConcurrently(body,body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_invoices",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("1");
    }
    @Test void permissionsBlockEmployeesAndHideWacForPurchasingOnlyUser() throws Exception {
        var owner=identities.credentials("owner").orElseThrow();
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner.id());
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner.id());
        mvc.perform(get("/api/purchases").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(request("0",List.of(item(spice,"1","10")))))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO roles(name) VALUES ('BUYER')");
        jdbc.update("INSERT INTO role_permissions SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.name='BUYER' AND p.code='PURCHASES_CREATE'");
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='BUYER'",owner.id());
        mvc.perform(get("/api/inventory").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].current_wac").doesNotExist());
        mvc.perform(get("/api/products").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void productUnitCannotChangeAfterPurchases() throws Exception {
        create(request("0",List.of(item(spice,"1","20"))));
        mvc.perform(put("/api/products/"+spice).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"name\":\"Spice\",\"code\":\"S\",\"category\":\"OTHER\",\"trackingType\":\"PIECE\",\"price\":\"45\",\"active\":true,\"version\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("PRODUCT_UNIT_LOCKED"));
    }
    @Test void cancellingPaidPurchaseRequiresRefundAndRestoresStockAndBalanceOnce() throws Exception {
        var original=request("20",List.of(item(spice,"2","10")));
        var purchase=create(original);
        String path="/api/purchases/"+purchase.get("id").asText()+"/cancel";
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Supplier credit note\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("REFUND_RECEIPT_REQUIRED"));
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Supplier credit note\",\"refundReceivedConfirmed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"));
        mvc.perform(get("/api/purchases/"+purchase.get("id").asText()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("outstanding").value("0.00"))
            .andExpect(jsonPath("cancellation.refund_received").value("20.00"));
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT current_wac FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE reference_type='PURCHASE_CANCEL'",Integer.class)).isEqualTo(1);
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Supplier credit note\",\"refundReceivedConfirmed\":true}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("ALREADY_CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE reference_type='PURCHASE_CANCEL'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_audit WHERE action='CANCEL'",Integer.class)).isEqualTo(1);
        assertThat(create(original).get("outstanding").asText()).isEqualTo("0.00");
    }
    @Test void laterMovementPreventsPurchaseCancellationWithoutChangingStock() throws Exception {
        var purchase=create(request("0",List.of(item(spice,"2","10"))));
        jdbc.update("INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,created_by) VALUES (?,?,'CORRECTION',1,'CORRECTION',?,?)",
            UUID.randomUUID(),spice,UUID.randomUUID(),identities.credentials("owner").orElseThrow().id());
        mvc.perform(post("/api/purchases/"+purchase.get("id").asText()+"/cancel").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Incorrect supplier\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("PURCHASE_REVALUATION_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_invoices WHERE id=?",String.class,UUID.fromString(purchase.get("id").asText()))).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,spice)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_cancellations",Integer.class)).isZero();
    }
    void runConcurrently(Map<String,Object> first,Map<String,Object> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<JsonNode> a=executor.submit(()->{start.await();return create(first);});
            Future<JsonNode> b=executor.submit(()->{start.await();return create(second);});
            start.countDown(); a.get(30,TimeUnit.SECONDS); b.get(30,TimeUnit.SECONDS);
        }
    }
}
