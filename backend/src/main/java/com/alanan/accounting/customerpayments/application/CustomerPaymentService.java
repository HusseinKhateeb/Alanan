package com.alanan.accounting.customerpayments.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.customerpayments.api.CustomerPaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerPaymentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CustomerPaymentService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public record AllocationReceipt(String invoiceId,String invoiceNumber,String amount) {}
    public record Receipt(String id,String customerName,String amount,String paymentMethodName,List<AllocationReceipt> allocations) {}
    private record Invoice(UUID id,String number,BigDecimal total,BigDecimal outstanding,String status,long version) {}
    private record Allocation(Invoice invoice,BigDecimal amount) {}

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_PAYMENTS_CREATE')")
    public Receipt create(CustomerPaymentRequest request,AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","customer-payment:"+user.id()+":"+request.requestId());
        String hash=fingerprint(request);
        var previous=jdbc.queryForList("SELECT id,request_hash FROM customer_payments WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return receipt((UUID)previous.getFirst().get("id"));
        }
        // Serialize all allocations for a customer. Sale creation holds a compatible read lock
        // until commit, so payment planning cannot miss an in-flight invoice for this customer.
        var customers=jdbc.queryForList("SELECT name FROM customers WHERE id=? AND NOT is_walk_in FOR UPDATE",request.customerId());
        if(customers.isEmpty()) fail("CUSTOMER_UNAVAILABLE");
        // Inactive customers may still have debt and can be paid.
        var methods=request.paymentMethodId()==null
            ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
            : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.paymentMethodId());
        if(methods.isEmpty()) fail("PAYMENT_METHOD_UNAVAILABLE");
        var method=methods.getFirst();
        List<Allocation> plan=request.mode()==CustomerPaymentRequest.Mode.AUTO ? autoPlan(request) : manualPlan(request);
        UUID paymentId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO customer_payments(id,customer_id,customer_name,amount,payment_method_id,payment_method_name,payment_date,notes,created_by,allocation_mode,request_id,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,paymentId,request.customerId(),customers.getFirst().get("name"),request.amount(),method.get("id"),method.get("name"),request.paymentDate(),request.notes(),user.id(),request.mode().name(),request.requestId(),hash);
        var changes=new ArrayList<Map<String,Object>>();
        for(var allocation:plan) {
            Invoice invoice=allocation.invoice();
            BigDecimal remaining=invoice.outstanding().subtract(allocation.amount());
            String status=remaining.signum()==0 ? "PAID" : "PARTIAL";
            jdbc.update("INSERT INTO customer_payment_allocations(payment_id,sale_id,customer_id,amount) VALUES (?,?,?,?)",paymentId,invoice.id(),request.customerId(),allocation.amount());
            jdbc.update("UPDATE sales SET paid=paid+?,status=?,version=version+1 WHERE id=?",allocation.amount(),status,invoice.id());
            Map<String,Object> change=Map.of("invoiceId",invoice.id(),"invoiceNumber",invoice.number(),"allocated",allocation.amount(),
                "oldOutstanding",invoice.outstanding(),"newOutstanding",remaining,"oldStatus",invoice.status(),"newStatus",status,
                "oldVersion",invoice.version(),"newVersion",invoice.version()+1,"paymentId",paymentId);
            changes.add(change);
            jdbc.update("INSERT INTO sale_audit(sale_id,user_id,action,new_values) VALUES (?,?,'CUSTOMER_PAYMENT',?::jsonb)",invoice.id(),user.id(),serialize(change));
        }
        jdbc.update("INSERT INTO customer_payment_audit(payment_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",paymentId,user.id(),serialize(Map.of("request",request,"invoiceChanges",changes)));
        return receipt(paymentId);
    }

    private List<Allocation> manualPlan(CustomerPaymentRequest request) {
        if(request.allocations().isEmpty()) fail("ALLOCATION_REQUIRED");
        var amounts=new TreeMap<UUID,BigDecimal>();
        BigDecimal sum=BigDecimal.ZERO;
        for(var allocation:request.allocations()) {
            if(amounts.putIfAbsent(allocation.invoiceId(),allocation.amount())!=null) fail("DUPLICATE_ALLOCATION");
            sum=sum.add(allocation.amount());
        }
        if(sum.compareTo(request.amount())!=0) fail("ALLOCATION_TOTAL_MISMATCH");
        var plan=new ArrayList<Allocation>();
        for(var entry:amounts.entrySet()) {
            var invoices=jdbc.query(INVOICE_QUERY+" WHERE p.id=? AND p.customer_id=? AND p.status<>'CANCELLED' FOR UPDATE OF p",
                (rs,row)->invoice(rs),entry.getKey(),request.customerId());
            if(invoices.isEmpty()) fail("ALLOCATION_INVOICE_UNAVAILABLE");
            var invoice=invoices.getFirst();
            if(entry.getValue().compareTo(invoice.outstanding())>0) fail("ALLOCATION_EXCEEDS_OUTSTANDING");
            plan.add(new Allocation(invoice,entry.getValue()));
        }
        return plan;
    }

    private List<Allocation> autoPlan(CustomerPaymentRequest request) {
        if(!request.allocations().isEmpty()) fail("AUTO_ALLOCATION_ITEMS_NOT_ALLOWED");
        BigDecimal remaining=request.amount();
        var plan=new ArrayList<Allocation>();
        // Bounded query batches. No allocations are written until the whole payment fits.
        long offset=0;
        while(remaining.signum()>0) {
            var invoices=jdbc.query(INVOICE_QUERY+"""
                 WHERE p.customer_id=? AND p.status<>'CANCELLED'
                 AND p.total>p.paid
                 ORDER BY p.invoice_date,p.created_at,p.id LIMIT 100 OFFSET ? FOR UPDATE OF p
                """,(rs,row)->invoice(rs),request.customerId(),offset);
            if(invoices.isEmpty()) fail("PAYMENT_EXCEEDS_OUTSTANDING");
            for(var invoice:invoices) {
                BigDecimal amount=remaining.min(invoice.outstanding());
                plan.add(new Allocation(invoice,amount)); remaining=remaining.subtract(amount);
                if(remaining.signum()==0) break;
            }
            offset+=invoices.size();
        }
        return plan;
    }

    private static final String INVOICE_QUERY="""
        SELECT p.id,p.invoice_number,p.total,p.status,p.version,
        p.total-p.paid outstanding
        FROM sales p
        """;
    private Invoice invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invoice(rs.getObject("id",UUID.class),rs.getString("invoice_number"),rs.getBigDecimal("total"),rs.getBigDecimal("outstanding"),rs.getString("status"),rs.getLong("version"));
    }
    private Receipt receipt(UUID id) {
        var allocations=jdbc.query("SELECT a.sale_id,p.invoice_number,a.amount FROM customer_payment_allocations a JOIN sales p ON p.id=a.sale_id WHERE a.payment_id=? ORDER BY p.invoice_date,p.created_at,p.id",
            (rs,row)->new AllocationReceipt(rs.getString("sale_id"),rs.getString("invoice_number"),rs.getBigDecimal("amount").toPlainString()),id);
        return jdbc.queryForObject("SELECT id,customer_name,amount,payment_method_name FROM customer_payments WHERE id=?",
            (rs,row)->new Receipt(rs.getString("id"),rs.getString("customer_name"),rs.getBigDecimal("amount").toPlainString(),rs.getString("payment_method_name"),allocations),id);
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize payment",ex); }
    }
    private String fingerprint(CustomerPaymentRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(request).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST,code); }
}
