package com.alanan.accounting.purchases.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.purchases.api.PurchaseRequest;
import com.alanan.accounting.purchases.domain.PurchaseMath;
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
public class PurchaseService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PurchaseService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public record Receipt(String id, String invoiceNumber, String total, String paid, String outstanding, String status) {}

    @Transactional
    @PreAuthorize("hasAuthority('PURCHASES_CREATE')")
    public Receipt create(PurchaseRequest request, AuthUser user) {
        // One request key is owned by one authenticated user. Concurrent retries wait for the original transaction.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "purchase:" + user.id() + ":" + request.requestId());
        String hash = fingerprint(request);
        var previous = jdbc.queryForList("SELECT id,request_hash FROM purchase_invoices WHERE created_by=? AND request_id=?", user.id(),request.requestId());
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return receipt((UUID)previous.getFirst().get("id"));
        }
        var suppliers = jdbc.queryForList("SELECT name FROM suppliers WHERE id=? AND active FOR SHARE", request.supplierId());
        if (suppliers.isEmpty()) PurchaseMath.fail("SUPPLIER_UNAVAILABLE");

        // Stable ordering prevents two multi-product purchases from deadlocking on product locks.
        Map<UUID,Map<String,Object>> products = new TreeMap<>();
        request.items().stream().map(PurchaseRequest.Item::productId).distinct().sorted().forEach(id -> {
            var rows = jdbc.queryForList("SELECT id,name,code,tracking_type,current_stock,current_wac FROM products WHERE id=? AND active FOR UPDATE", id);
            if (rows.isEmpty()) PurchaseMath.fail("PRODUCT_UNAVAILABLE");
            products.put(id,rows.getFirst());
        });
        BigDecimal total = BigDecimal.ZERO;
        Map<UUID,BigDecimal> quantities = new HashMap<>();
        Map<UUID,BigDecimal> values = new HashMap<>();
        for (var item : request.items()) {
            if ("PIECE".equals(products.get(item.productId()).get("tracking_type")) && item.quantity().stripTrailingZeros().scale() > 0)
                PurchaseMath.fail("WHOLE_PIECES_REQUIRED");
            total = total.add(PurchaseMath.lineTotal(item.quantity(),item.unitCost()));
            quantities.merge(item.productId(),item.quantity(),BigDecimal::add);
            values.merge(item.productId(),item.quantity().multiply(item.unitCost()),BigDecimal::add);
        }
        total = PurchaseMath.money(total);
        if (total.precision() > 18) PurchaseMath.fail("PURCHASE_AMOUNT_INVALID");
        if (request.paidNow().compareTo(total) > 0) PurchaseMath.fail("PAYMENT_EXCEEDS_TOTAL");
        Map<String,Object> paymentMethod = null;
        if (request.paidNow().signum() > 0) {
            var methods = request.paymentMethodId() == null
                ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
                : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.paymentMethodId());
            if (methods.isEmpty()) PurchaseMath.fail("PAYMENT_METHOD_UNAVAILABLE");
            paymentMethod=methods.getFirst();
        }
        // WAC is calculated from aggregated unrounded acquisition value, once per product.
        var effects = new ArrayList<Map<String,Object>>();
        for (var entry : products.entrySet()) {
            var product=entry.getValue();
            BigDecimal stock=(BigDecimal)product.get("current_stock");
            BigDecimal newStock=stock.add(quantities.get(entry.getKey()));
            BigDecimal wac=PurchaseMath.wac(stock,(BigDecimal)product.get("current_wac"),quantities.get(entry.getKey()),values.get(entry.getKey()));
            jdbc.update("UPDATE products SET current_stock=?,current_wac=?,version=version+1 WHERE id=?",newStock,wac,entry.getKey());
            var effect=new LinkedHashMap<String,Object>();
            effect.put("productId",entry.getKey()); effect.put("oldStock",stock); effect.put("newStock",newStock);
            effect.put("oldWac",product.get("current_wac")); effect.put("newWac",wac); effects.add(effect);
        }
        int year=request.invoiceDate().getYear();
        Long number=jdbc.queryForObject("""
            INSERT INTO invoice_counters(kind,invoice_year,next_value) VALUES ('PUR',?,1)
            ON CONFLICT(kind,invoice_year) DO UPDATE SET next_value=invoice_counters.next_value+1 RETURNING next_value
            """,Long.class,year);
        String invoiceNumber=String.format(Locale.ROOT,"PUR-%d-%06d",year,number);
        UUID invoiceId=UUID.randomUUID();
        String status=request.paidNow().signum()==0 ? "OPEN" : request.paidNow().compareTo(total)==0 ? "PAID" : "PARTIAL";
        jdbc.update("""
            INSERT INTO purchase_invoices(id,invoice_number,supplier_id,supplier_name,invoice_date,supplier_reference,notes,total,status,created_by,request_id,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, invoiceId,invoiceNumber,request.supplierId(),suppliers.getFirst().get("name"),request.invoiceDate(),
            request.supplierReference(),request.notes(),total,status,user.id(),request.requestId(),hash);
        int line=0;
        for (var item : request.items()) {
            var product=products.get(item.productId()); UUID itemId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO purchase_items(id,purchase_invoice_id,line_number,product_id,product_name,product_code,tracking_type,quantity,unit_cost,line_total)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,itemId,invoiceId,++line,item.productId(),product.get("name"),product.get("code"),product.get("tracking_type"),item.quantity(),item.unitCost(),PurchaseMath.lineTotal(item.quantity(),item.unitCost()));
            jdbc.update("""
                INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,purchase_item_id,unit_cost,notes,created_by)
                VALUES (?,?,'PURCHASE',?,'PURCHASE',?,?,?,?,?)
                """,UUID.randomUUID(),item.productId(),item.quantity(),invoiceId,itemId,item.unitCost(),request.notes(),user.id());
        }
        UUID paymentId=null;
        if (paymentMethod != null) {
            paymentId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO supplier_payments(id,supplier_id,supplier_name,amount,payment_method_id,payment_method_name,payment_date,notes,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,paymentId,request.supplierId(),suppliers.getFirst().get("name"),request.paidNow(),paymentMethod.get("id"),paymentMethod.get("name"),request.invoiceDate(),request.notes(),user.id());
            jdbc.update("INSERT INTO supplier_payment_allocations(payment_id,purchase_invoice_id,supplier_id,amount) VALUES (?,?,?,?)",paymentId,invoiceId,request.supplierId(),request.paidNow());
        }
        var audit=new LinkedHashMap<String,Object>(); audit.put("request",request); audit.put("invoiceNumber",invoiceNumber);
        audit.put("total",total); audit.put("inventoryEffects",effects); audit.put("paymentId",paymentId);
        jdbc.update("INSERT INTO purchase_audit(purchase_invoice_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",invoiceId,user.id(),serialize(audit));
        return receipt(invoiceId);
    }
    private Receipt receipt(UUID id) {
        return jdbc.queryForObject("""
            SELECT p.id,p.invoice_number,p.total,p.status,COALESCE(a.paid,0) paid,p.total-COALESCE(a.paid,0) outstanding
            FROM purchase_invoices p LEFT JOIN (SELECT purchase_invoice_id,sum(amount) paid FROM supplier_payment_allocations GROUP BY purchase_invoice_id) a
            ON a.purchase_invoice_id=p.id WHERE p.id=?
            """,(rs,row)-> new Receipt(rs.getString("id"),rs.getString("invoice_number"),rs.getBigDecimal("total").toPlainString(),
                PurchaseMath.money(rs.getBigDecimal("paid")).toPlainString(),PurchaseMath.money(rs.getBigDecimal("outstanding")).toPlainString(),rs.getString("status")),id);
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize purchase",ex); }
    }
    private String fingerprint(PurchaseRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(request).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
