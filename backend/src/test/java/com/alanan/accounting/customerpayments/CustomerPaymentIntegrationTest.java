package com.alanan.accounting.customerpayments;

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
class CustomerPaymentIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17.9-alpine");
    static final String PASSWORD=UUID.randomUUID().toString();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
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
    UUID customer,product,owner;
    String token;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,products,invoice_counters CASCADE");
        jdbc.update("DELETE FROM customers WHERE NOT is_walk_in");
        bootstrap.run(new DefaultApplicationArguments());
        owner=identities.credentials("owner").orElseThrow().id();
        token=auth.login("owner", PASSWORD, UUID.randomUUID().toString()).access().accessToken();
        customer=UUID.randomUUID();product=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,name) VALUES (?,'Customer')",customer);
        jdbc.update("INSERT INTO products(id,code,name,category,tracking_type,default_selling_price) VALUES (?,'S','Spice','SPICE','WEIGHT',45)",product);
    }
    UUID sale(String date,String total,String paid) throws Exception {
        var body=Map.of("requestId",UUID.randomUUID(),"customerId",customer,"invoiceDate",date,"paidNow",paid,
            "items",List.of(Map.of("productId",product,"quantity","1","unitPrice",total,"mode","BY_WEIGHT")));
        var response=mvc.perform(post("/api/sales").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
    }
    Map<String,Object> request(String amount,String mode,List<Map<String,Object>> allocations) {
        var body=new LinkedHashMap<String,Object>();
        body.put("requestId",UUID.randomUUID());body.put("customerId",customer);body.put("paymentDate","2026-02-01");
        body.put("amount",amount);body.put("mode",mode);body.put("allocations",allocations);return body;
    }
    Map<String,Object> allocation(UUID invoice,String amount) { return Map.of("invoiceId",invoice,"amount",amount); }
    org.springframework.mock.web.MockHttpServletResponse postPayment(Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/customer-payments").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
            .andReturn().getResponse();
    }
    JsonNode save(Map<String,Object> body) throws Exception {
        var response=postPayment(body); assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString());
    }
    @Test void automaticPaymentUsesOldestInvoicesAndUpdatesBalancesAtomically() throws Exception {
        UUID newer=sale("2026-01-10","100","0"), older=sale("2026-01-01","100","20");
        var result=save(request("120","AUTO",List.of()));
        assertThat(result.at("/allocations/0/invoiceId").asText()).isEqualTo(older.toString());
        assertThat(result.at("/allocations/0/amount").asText()).isEqualTo("80.00");
        assertThat(result.at("/allocations/1/invoiceId").asText()).isEqualTo(newer.toString());
        assertThat(result.at("/allocations/1/amount").asText()).isEqualTo("40.00");
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE id=?",String.class,older)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE id=?",String.class,newer)).isEqualTo("PARTIAL");
        mvc.perform(get("/api/customer-balances").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].outstanding").value("60.00"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payment_audit",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_audit WHERE action='CUSTOMER_PAYMENT'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_stock FROM products WHERE id=?",java.math.BigDecimal.class,product)).isEqualByComparingTo("-2");
        mvc.perform(get("/api/customer-payments/"+result.get("id").asText()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("allocations.length()").value(2));
    }
    @Test void cancellationReopensInvoiceAndPreservesPaymentHistory() throws Exception {
        UUID invoice=sale("2026-01-01","100","20");
        var payment=save(request("80","MANUAL",List.of(allocation(invoice,"80"))));
        String path="/api/customer-payments/"+payment.get("id").asText()+"/cancel";
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate receipt\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("errorCode").value("REFUND_CONFIRMATION_REQUIRED"));
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate receipt\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT paid FROM sales WHERE id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE id=?",String.class,invoice)).isEqualTo("PARTIAL");
        mvc.perform(get("/api/customer-payments/"+payment.get("id").asText()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("cancellation.reason").value("Duplicate receipt"))
            .andExpect(jsonPath("allocations.length()").value(1));
        mvc.perform(get("/api/customer-balances").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("items[0].outstanding").value("80.00"));
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate receipt\",\"refundConfirmed\":true}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payment_audit WHERE action='CANCEL'",Integer.class)).isEqualTo(1);
    }
    @Test void paymentHardDeletionRequiresRefundAndUnlinksItsInvoice() throws Exception {
        UUID invoice=sale("2026-01-01","100","0");
        var payment=save(request("100","MANUAL",List.of(allocation(invoice,"100"))));
        String deletion="/api/admin/deletions/customer-payments/"+payment.get("id").asText();
        mvc.perform(delete(deletion).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("DELETE_REFUND_REQUIRED"));
        mvc.perform(post("/api/customer-payments/"+payment.get("id").asText()+"/cancel")
            .header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Refunded\",\"refundConfirmed\":true}")) .andExpect(status().isOk());
        mvc.perform(delete(deletion).header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate\"}")) .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payments",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT paid FROM sales WHERE id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hard_deletion_audit WHERE entity_type='CUSTOMER_PAYMENT'",Integer.class)).isEqualTo(1);
    }
    @Test void paymentCannotBeReversedAfterItsSaleWasCancelled() throws Exception {
        UUID invoice=sale("2026-01-01","100","0");
        var payment=save(request("100","MANUAL",List.of(allocation(invoice,"100"))));
        mvc.perform(post("/api/sales/"+invoice+"/cancel").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Wrong invoice\",\"refundConfirmed\":true}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/customer-payments/"+payment.get("id").asText()+"/cancel").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"reason\":\"Duplicate\",\"refundConfirmed\":true}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("errorCode").value("PAYMENT_INVOICE_CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT active FROM customer_payment_allocations WHERE payment_id=?",Boolean.class,UUID.fromString(payment.get("id").asText()))).isTrue();
    }
    @Test void manualAllocationsHonorSelectedInvoicesAndAllowInactiveCustomerDebt() throws Exception {
        UUID first=sale("2026-01-01","100","0"), second=sale("2026-01-02","50","0");
        jdbc.update("UPDATE customers SET active=false WHERE id=?",customer);
        save(request("70","MANUAL",List.of(allocation(first,"20"),allocation(second,"50"))));
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE id=?",String.class,first)).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT status FROM sales WHERE id=?",String.class,second)).isEqualTo("PAID");
    }
    @Test void overpaymentMismatchAndDuplicateAllocationsLeaveNoPaymentOrAudit() throws Exception {
        UUID invoice=sale("2026-01-01","100","0");
        var bad=List.of(request("101","AUTO",List.of()),request("100","MANUAL",List.of(allocation(invoice,"50"))),
            request("101","MANUAL",List.of(allocation(invoice,"101"))),
            request("100","MANUAL",List.of(allocation(invoice,"50"),allocation(invoice,"50"))));
        for(var body:bad) assertThat(postPayment(body).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payments",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payment_audit",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM sales WHERE id=?",Integer.class,invoice)).isZero();
    }
    @Test void rejectsOtherCustomerAndPaidInvoicesWithoutPartialWrites() throws Exception {
        UUID valid=sale("2026-01-01","100","0"), invalid=sale("2026-01-02","100","0");
        jdbc.update("UPDATE sales SET status='PAID',paid=total WHERE id=?",invalid);
        assertThat(postPayment(request("40","MANUAL",List.of(allocation(valid,"20"),allocation(invalid,"20")))).getStatus()).isEqualTo(400);
        var other=UUID.randomUUID();jdbc.update("INSERT INTO customers(id,name) VALUES (?,'Other')",other);
        var body=request("20","MANUAL",List.of(allocation(valid,"20")));body.put("customerId",other);
        assertThat(postPayment(body).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payments",Integer.class)).isZero();
    }
    @Test void sameRequestReplaysButChangedPayloadConflicts() throws Exception {
        sale("2026-01-01","100","0");
        var body=request("40","AUTO",List.of());
        var first=save(body); assertThat(save(body)).isEqualTo(first);
        body.put("amount","30");
        assertThat(postPayment(body).getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payments",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentDistinctPaymentsCannotOverallocate() throws Exception {
        sale("2026-01-01","100","0");
        assertThat(concurrent(request("70","AUTO",List.of()),request("70","AUTO",List.of()))).containsExactlyInAnyOrder(201,400);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM customer_payment_allocations",java.math.BigDecimal.class)).isEqualByComparingTo("70");
    }
    @Test void concurrentSameKeyPaymentsHaveOneEffect() throws Exception {
        sale("2026-01-01","100","0");
        var body=request("70","AUTO",List.of());
        assertThat(concurrent(body,body)).containsExactlyInAnyOrder(201,201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payments",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_payment_audit",Integer.class)).isEqualTo(1);
    }
    @Test void permissionChecksProtectPaymentWritesHistoryAndReferences() throws Exception {
        sale("2026-01-01","100","0");
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        jdbc.update("INSERT INTO user_roles SELECT ?,id FROM roles WHERE name='EMPLOYEE'",owner);
        assertThat(postPayment(request("10","AUTO",List.of())).getStatus()).isEqualTo(403);
        mvc.perform(get("/api/customer-payments").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        jdbc.update("DELETE FROM user_roles WHERE user_id=?",owner);
        mvc.perform(get("/api/customer-payments/open-invoices").param("customerId",customer.toString()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void historyPreservesCustomerNameAndOriginalSalePayment() throws Exception {
        UUID invoice=sale("2026-01-01","100","20");
        var result=save(request("30","AUTO",List.of()));
        assertThat(jdbc.queryForObject("SELECT paid_at_sale FROM sales WHERE id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("20");
        jdbc.update("UPDATE customers SET name='Changed' WHERE id=?",customer);
        mvc.perform(get("/api/customer-payments").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1));
        mvc.perform(get("/api/customer-payments/"+result.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("customer_name").value("Customer"));
        mvc.perform(get("/api/customer-payments/open-invoices").param("customerId",customer.toString()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("items[0].outstanding").value("50.00"));
    }
    @Test void walkInCannotReceiveStandalonePayments() throws Exception {
        var body=request("10","AUTO",List.of()); body.put("customerId", "00000000-0000-0000-0000-000000000001");
        assertThat(postPayment(body).getStatus()).isEqualTo(400);
    }
    List<Integer> concurrent(Map<String,Object> first,Map<String,Object> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return postPayment(first).getStatus();});
            var b=pool.submit(()->{start.await();return postPayment(second).getStatus();});
            start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
}
