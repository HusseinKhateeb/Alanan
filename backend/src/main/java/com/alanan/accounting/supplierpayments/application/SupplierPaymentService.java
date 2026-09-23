package com.alanan.accounting.supplierpayments.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.supplierpayments.api.SupplierPaymentRequest;
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
public class SupplierPaymentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SupplierPaymentService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public record AllocationReceipt(String invoiceId,String invoiceNumber,String amount) {}
    public record Receipt(String id,String supplierName,String amount,String paymentMethodName,List<AllocationReceipt> allocations) {}
    private record Invoice(UUID id,String number,BigDecimal total,BigDecimal outstanding,String status,long version) {}
    private record Allocation(Invoice invoice,BigDecimal amount) {}

    @Transactional
    @PreAuthorize("hasAuthority('SUPPLIER_PAYMENTS_CREATE')")
    public Receipt create(SupplierPaymentRequest request,AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","supplier-payment:"+user.id()+":"+request.requestId());
        String hash=fingerprint(request);
        var previous=jdbc.queryForList("SELECT id,request_hash FROM supplier_payments WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return receipt((UUID)previous.getFirst().get("id"));
        }
        // Serialize all allocations for a supplier. Purchase creation holds a compatible read lock
        // until commit, so payment planning cannot miss an in-flight invoice for this supplier.
        var suppliers=jdbc.queryForList("SELECT name FROM suppliers WHERE id=? FOR UPDATE",request.supplierId());
        if(suppliers.isEmpty()) fail("SUPPLIER_UNAVAILABLE");
        // Inactive suppliers may still have debt and can be paid.
        var methods=request.paymentMethodId()==null
            ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
            : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.paymentMethodId());
        if(methods.isEmpty()) fail("PAYMENT_METHOD_UNAVAILABLE");
        var method=methods.getFirst();
        List<Allocation> plan=request.mode()==SupplierPaymentRequest.Mode.AUTO ? autoPlan(request) : manualPlan(request);
        UUID paymentId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO supplier_payments(id,supplier_id,supplier_name,amount,payment_method_id,payment_method_name,payment_date,notes,created_by,allocation_mode,request_id,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,paymentId,request.supplierId(),suppliers.getFirst().get("name"),request.amount(),method.get("id"),method.get("name"),request.paymentDate(),request.notes(),user.id(),request.mode().name(),request.requestId(),hash);
        var changes=new ArrayList<Map<String,Object>>();
        for(var allocation:plan) {
            Invoice invoice=allocation.invoice();
            BigDecimal remaining=invoice.outstanding().subtract(allocation.amount());
            String status=remaining.signum()==0 ? "PAID" : "PARTIAL";
            jdbc.update("INSERT INTO supplier_payment_allocations(payment_id,purchase_invoice_id,supplier_id,amount) VALUES (?,?,?,?)",paymentId,invoice.id(),request.supplierId(),allocation.amount());
            jdbc.update("UPDATE purchase_invoices SET status=?,version=version+1 WHERE id=?",status,invoice.id());
            Map<String,Object> change=Map.of("invoiceId",invoice.id(),"invoiceNumber",invoice.number(),"allocated",allocation.amount(),
                "oldOutstanding",invoice.outstanding(),"newOutstanding",remaining,"oldStatus",invoice.status(),"newStatus",status,
                "oldVersion",invoice.version(),"newVersion",invoice.version()+1,"paymentId",paymentId);
            changes.add(change);
            jdbc.update("INSERT INTO purchase_audit(purchase_invoice_id,user_id,action,new_values) VALUES (?,?,'SUPPLIER_PAYMENT',?::jsonb)",invoice.id(),user.id(),serialize(change));
        }
        jdbc.update("INSERT INTO supplier_payment_audit(payment_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",paymentId,user.id(),serialize(Map.of("request",request,"invoiceChanges",changes)));
        return receipt(paymentId);
    }

    private List<Allocation> manualPlan(SupplierPaymentRequest request) {
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
            var invoices=jdbc.query(INVOICE_QUERY+" WHERE p.id=? AND p.supplier_id=? AND p.status<>'CANCELLED' FOR UPDATE OF p",
                (rs,row)->invoice(rs),entry.getKey(),request.supplierId());
            if(invoices.isEmpty()) fail("ALLOCATION_INVOICE_UNAVAILABLE");
            var invoice=invoices.getFirst();
            if(entry.getValue().compareTo(invoice.outstanding())>0) fail("ALLOCATION_EXCEEDS_OUTSTANDING");
            plan.add(new Allocation(invoice,entry.getValue()));
        }
        return plan;
    }

    private List<Allocation> autoPlan(SupplierPaymentRequest request) {
        if(!request.allocations().isEmpty()) fail("AUTO_ALLOCATION_ITEMS_NOT_ALLOWED");
        BigDecimal remaining=request.amount();
        var plan=new ArrayList<Allocation>();
        // Bounded query batches. No allocations are written until the whole payment fits.
        long offset=0;
        while(remaining.signum()>0) {
            var invoices=jdbc.query(INVOICE_QUERY+"""
                 WHERE p.supplier_id=? AND p.status<>'CANCELLED'
                 AND p.total>COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id),0)
                 ORDER BY p.invoice_date,p.created_at,p.id LIMIT 100 OFFSET ? FOR UPDATE OF p
                """,(rs,row)->invoice(rs),request.supplierId(),offset);
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
        p.total-COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id),0) outstanding
        FROM purchase_invoices p
        """;
    private Invoice invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invoice(rs.getObject("id",UUID.class),rs.getString("invoice_number"),rs.getBigDecimal("total"),rs.getBigDecimal("outstanding"),rs.getString("status"),rs.getLong("version"));
    }
    private Receipt receipt(UUID id) {
        var allocations=jdbc.query("SELECT a.purchase_invoice_id,p.invoice_number,a.amount FROM supplier_payment_allocations a JOIN purchase_invoices p ON p.id=a.purchase_invoice_id WHERE a.payment_id=? ORDER BY p.invoice_date,p.created_at,p.id",
            (rs,row)->new AllocationReceipt(rs.getString("purchase_invoice_id"),rs.getString("invoice_number"),rs.getBigDecimal("amount").toPlainString()),id);
        return jdbc.queryForObject("SELECT id,supplier_name,amount,payment_method_name FROM supplier_payments WHERE id=?",
            (rs,row)->new Receipt(rs.getString("id"),rs.getString("supplier_name"),rs.getBigDecimal("amount").toPlainString(),rs.getString("payment_method_name"),allocations),id);
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize payment",ex); }
    }
    private String fingerprint(SupplierPaymentRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(request).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST,code); }
}
