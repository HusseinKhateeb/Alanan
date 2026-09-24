package com.alanan.accounting.operations;

import com.alanan.accounting.auth.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
        jdbc.execute("TRUNCATE users,products,expense_categories,container_types,suppliers CASCADE");
        jdbc.update("DELETE FROM customers WHERE NOT is_walk_in");
        bootstrap.run(new DefaultApplicationArguments());
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
    @Test void hardDeletionOfExpenseAndWasteKeepsAuditAndRestoresStock() throws Exception {
        var expense=save("expenses",expense());
        var waste=save("waste",waste("2"));
        for(var entry:List.of(Map.entry("expenses",expense),Map.entry("waste",waste))) {
            mvc.perform(delete("/api/admin/deletions/"+entry.getKey()+"/"+entry.getValue().get("id").asText())
                .header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"reason\":\"Entered in error\"}")) .andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waste_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",BigDecimal.class,product)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hard_deletion_audit",Integer.class)).isEqualTo(2);
    }
    @Test void expenseRecorderCanQuickCreateTypeWithoutSettingsPermission() throws Exception {
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,FALSE FROM permissions WHERE code='SETTINGS_MANAGE'",owner);
        mvc.perform(post("/api/expense-categories/quick").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"name\":\"Delivery\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("name").value("Delivery"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense_categories WHERE name='Delivery'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master_data_audit WHERE entity_type='expense-categories'",Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,FALSE FROM permissions WHERE code='EXPENSES_CREATE'",owner);
        mvc.perform(post("/api/expense-categories/quick").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"name\":\"Travel\"}")) .andExpect(status().isForbidden());
    }
    @Test void wasteRecorderCanQuickCreateProductWithoutSettingsPermission() throws Exception {
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,FALSE FROM permissions WHERE code='SETTINGS_MANAGE'",owner);
        String body="{\"name\":\"Old honey\",\"code\":\"OLD-H\",\"category\":\"HONEY\",\"trackingType\":\"PIECE\",\"price\":\"25.00\"}";
        mvc.perform(post("/api/products/quick-waste").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("tracking_type").value("PIECE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE code='OLD-H'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master_data_audit WHERE entity_type='products'",Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO user_permission_overrides(user_id,permission_id,granted) SELECT ?,id,FALSE FROM permissions WHERE code='INVENTORY_ADJUST'",owner);
        mvc.perform(post("/api/products/quick-waste").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body.replace("OLD-H","OLD-H2"))) .andExpect(status().isForbidden());
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
    @Test void csvExportIncludesPeriodRowsAndEscapesSpreadsheetFormulas() throws Exception {
        jdbc.update("UPDATE expense_categories SET name='=2+2' WHERE id=?",category);
        save("expenses",expense());
        String path="/api/reports/expenses/csv?from=2026-01-01&to=2026-01-31";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var response=mvc.perform(get(path).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type",org.hamcrest.Matchers.containsString("text/csv")))
            .andReturn().getResponse();
        String csv=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("expense_date,category_name,payment_method_name,amount,notes", "\"'=2+2\"", "\"125.50\"");
        mvc.perform(get("/api/reports/profit/csv?from=2026-01-02&to=2026-01-01").header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void cancelledExpenseLeavesHistoryAndDisappearsFromDashboardTotals() throws Exception {
        var body=expense();
        body.put("expenseDate",LocalDate.now(ZoneId.of("Asia/Hebron")).toString());
        var record=save("expenses",body);
        mvc.perform(get("/api/dashboard").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("expensesMonth").value("125.50"));
        String path="/api/expenses/"+record.get("id").asText()+"/cancel";
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Entered twice\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("cancellation.reason").value("Entered twice"));
        mvc.perform(get("/api/expenses").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items[0].cancellation_reason").value("Entered twice"));
        mvc.perform(get("/api/dashboard").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("expensesMonth").value("0.00"));
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Entered twice\",\"refundConfirmed\":true}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("ALREADY_CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense_audit WHERE action='CANCEL'",Integer.class)).isEqualTo(1);
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
    @Test void auditHistoryCombinesEventsAndFiltersWithoutChangingRecords() throws Exception {
        save("expenses",expense());
        save("waste",waste("1"));
        mvc.perform(get("/api/audit").param("type","EXPENSE").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1))
            .andExpect(jsonPath("items[0].entity_type").value("EXPENSE"))
            .andExpect(jsonPath("items[0].new_values").exists());
        mvc.perform(get("/api/audit").param("type","WASTE").param("actor",owner.toString()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1));
        mvc.perform(get("/api/audit").param("from","2099-01-01").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0));
        mvc.perform(get("/api/audit").param("from","2026-02-01").param("to","2026-01-01").header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest());
    }
    @Test void auditHistoryRequiresAuditCapability() throws Exception {
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        mvc.perform(get("/api/audit").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void dashboardUsesRealTotalsAndSeparatesUnknownContainerCost() throws Exception {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Hebron"));
        UUID sale=UUID.randomUUID(),container=UUID.randomUUID(),supplier=UUID.randomUUID(),purchase=UUID.randomUUID();
        jdbc.update("INSERT INTO container_types(id,name,default_price) VALUES (?,'Small',10)",container);
        jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,'Supplier')",supplier);
        jdbc.update("""
            INSERT INTO sales(id,invoice_number,customer_id,customer_name,invoice_date,total,paid,paid_at_sale,payment_method_id,payment_method_name,
            status,negative_stock,created_by,request_id,request_hash) VALUES (?,'SAL-TEST-1','00000000-0000-0000-0000-000000000001',
            'Walk-in',?,120,120,120,(SELECT id FROM payment_methods WHERE is_default),'Cash','PAID',false,?,?,'test')
            """,sale,today,owner,UUID.randomUUID());
        jdbc.update("""
            INSERT INTO sale_items(id,sale_id,line_number,product_id,item_name,item_type,selling_mode,quantity,unit_price,line_total,cost_at_sale,cost_total)
            VALUES (?,?,1,?,'Spice','WEIGHT_PRODUCT','BY_WEIGHT',2,50,100,20,40)
            """,UUID.randomUUID(),sale,product);
        jdbc.update("""
            INSERT INTO sale_items(id,sale_id,line_number,container_type_id,item_name,item_type,selling_mode,quantity,unit_price,line_total)
            VALUES (?,?,2,?,'Small','SPICE_CONTAINER','BY_PIECE',2,10,20)
            """,UUID.randomUUID(),sale,container);
        jdbc.update("INSERT INTO purchase_invoices(id,invoice_number,supplier_id,supplier_name,invoice_date,total,status,created_by,request_id,request_hash) VALUES (?,'PUR-TEST-1',?,'Supplier',?,30,'OPEN',?,?,'test')",
            purchase,supplier,today,owner,UUID.randomUUID());
        jdbc.update("INSERT INTO expenses(id,category_id,category_name,amount,expense_date,payment_method_id,payment_method_name,created_by,request_id,request_hash) VALUES (?,?, 'Rent',10,?,(SELECT id FROM payment_methods WHERE is_default),'Cash',?,?,'test')",
            UUID.randomUUID(),category,today,owner,UUID.randomUUID());
        mvc.perform(get("/api/dashboard").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("salesToday").value("120.00")).andExpect(jsonPath("salesMonth").value("120.00"))
            .andExpect(jsonPath("containerRevenueMonth").value("20.00"))
            .andExpect(jsonPath("knownCogsMonth").value("40.00"))
            .andExpect(jsonPath("knownGrossProfitMonth").value("60.00"))
            .andExpect(jsonPath("estimatedNetProfitMonth").value("50.00"))
            .andExpect(jsonPath("purchasesMonth").value("30.00"))
            .andExpect(jsonPath("supplierDebt").value("30.00"));
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        mvc.perform(get("/api/dashboard").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("salesMonth").value("120.00"))
            .andExpect(jsonPath("knownGrossProfitMonth").doesNotExist())
            .andExpect(jsonPath("purchasesMonth").doesNotExist())
            .andExpect(jsonPath("expensesMonth").doesNotExist());
    }
    @Test void reportsUsePeriodTotalsExcludeCancellationsAndProtectProfit() throws Exception {
        UUID customer=UUID.randomUUID(),supplier=UUID.randomUUID(),container=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,name) VALUES (?,'Customer')",customer);
        jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,'Supplier')",supplier);
        jdbc.update("INSERT INTO container_types(id,name,default_price) VALUES (?,'Small',10)",container);
        var saleBody=Map.of("requestId",UUID.randomUUID(),"customerId",customer,"invoiceDate","2026-01-01","paidNow","20",
            "items",List.of(Map.of("productId",product,"mode","BY_WEIGHT","quantity","1","unitPrice","50"),
                Map.of("containerTypeId",container,"mode","BY_PIECE","quantity","1","unitPrice","10")));
        var sale=json.readTree(mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(saleBody))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var purchaseBody=Map.of("requestId",UUID.randomUUID(),"supplierId",supplier,"invoiceDate","2026-01-01","paidNow","20",
            "items",List.of(Map.of("productId",product,"quantity","1","unitCost","100")));
        var purchase=json.readTree(mvc.perform(post("/api/purchases").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json.writeValueAsString(purchaseBody))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var expenseRecord=save("expenses",expense());
        String dates="?from=2026-01-01&to=2026-01-31";
        mvc.perform(get("/api/reports/sales"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("totals.invoice_count").value(1)).andExpect(jsonPath("totals.total").value("60.00"))
            .andExpect(jsonPath("totals.outstanding").value("40.00")).andExpect(jsonPath("rows.items.length()").value(1));
        mvc.perform(get("/api/reports/purchases"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("totals.total").value("100.00")).andExpect(jsonPath("totals.paid").value("20.00"));
        mvc.perform(get("/api/reports/expenses"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("totals.total").value("125.50"));
        mvc.perform(get("/api/reports/suppliers"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("totals.purchased").value("100.00")).andExpect(jsonPath("totals.paid").value("20.00"))
            .andExpect(jsonPath("totals.current_outstanding").value("80.00"));
        mvc.perform(get("/api/reports/profit"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("totals.known_revenue").value("50.00"))
            .andExpect(jsonPath("totals.known_cost").value("20.00"))
            .andExpect(jsonPath("totals.unknown_cost_revenue").value("10.00"))
            .andExpect(jsonPath("totals.known_margin_less_expenses").value("-95.50"));
        mvc.perform(post("/api/sales/"+sale.get("id").asText()+"/cancel").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"reason\":\"Wrong invoice\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/expenses/"+expenseRecord.get("id").asText()+"/cancel").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"reason\":\"Entered twice\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/reports/sales"+dates).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("totals.total").value("0.00"));
        mvc.perform(get("/api/reports/expenses"+dates).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("totals.total").value("0.00"));
        mvc.perform(get("/api/reports/profit"+dates).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("totals.known_revenue").value("0.00"))
            .andExpect(jsonPath("totals.unknown_cost_revenue").value("0.00"));
        UUID paymentId=jdbc.queryForObject("SELECT payment_id FROM supplier_payment_allocations WHERE purchase_invoice_id=?",UUID.class,UUID.fromString(purchase.get("id").asText()));
        mvc.perform(post("/api/supplier-payments/"+paymentId+"/cancel").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"reason\":\"Bank reversal\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/reports/suppliers"+dates).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("totals.paid").value("0.00"))
            .andExpect(jsonPath("totals.current_outstanding").value("100.00"));
        mvc.perform(get("/api/reports/sales?from=2026-02-01&to=2026-02-28").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("totals.total").value("0.00"));
        mvc.perform(get("/api/reports/sales?from=2026-02-01&to=2026-01-01").header("Authorization","Bearer "+token))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("INVALID_DATE_RANGE"));
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        mvc.perform(get("/api/reports/sales"+dates).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/profit"+dates).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/purchases"+dates).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
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
