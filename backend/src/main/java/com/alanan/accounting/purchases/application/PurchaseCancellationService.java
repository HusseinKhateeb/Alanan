package com.alanan.accounting.purchases.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.purchases.api.CancelPurchaseRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseCancellationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PurchaseCancellationService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional
    @PreAuthorize("hasAuthority('PURCHASES_CREATE') and hasAuthority('AUDIT_VIEW')")
    public Map<String,Object> cancel(UUID id,CancelPurchaseRequest request,AuthUser user) {
        return cancelInternal(id,request,user);
    }

    // Only the admin-gated HardDeletionService calls this inside its transaction.
    public Map<String,Object> reverseForHardDeletion(UUID id,CancelPurchaseRequest request,AuthUser user) {
        return cancelInternal(id,request,user);
    }

    private Map<String,Object> cancelInternal(UUID id,CancelPurchaseRequest request,AuthUser user) {
        var reference=jdbc.queryForList("SELECT supplier_id FROM purchase_invoices WHERE id=?",id);
        if(reference.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        UUID supplierId=(UUID)reference.getFirst().get("supplier_id");
        // Supplier payments lock the supplier before invoices. Keep the same lock order.
        jdbc.queryForList("SELECT id FROM suppliers WHERE id=? FOR UPDATE",supplierId);
        var rows=jdbc.queryForList("SELECT id,invoice_number,status,total FROM purchase_invoices WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var invoice=rows.getFirst();
        if("CANCELLED".equals(invoice.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        BigDecimal paid=jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM supplier_payment_allocations WHERE purchase_invoice_id=? AND active",BigDecimal.class,id);
        Map<String,Object> method=null;
        if(paid.signum()>0) {
            if(!request.refundReceivedConfirmed()) throw new ApiException(HttpStatus.BAD_REQUEST,"REFUND_RECEIPT_REQUIRED");
            var methods=request.refundMethodId()==null
                ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
                : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.refundMethodId());
            if(methods.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_METHOD_UNAVAILABLE");
            method=methods.getFirst();
        }
        var items=jdbc.queryForList("SELECT id,product_id,quantity,unit_cost FROM purchase_items WHERE purchase_invoice_id=? ORDER BY product_id,id",id);
        var effects=creationEffects(id);
        var products=new TreeMap<UUID,Map<String,Object>>();
        for(var item:items) {
            UUID productId=(UUID)item.get("product_id");
            if(!products.containsKey(productId)) products.put(productId,jdbc.queryForMap("SELECT current_stock,current_wac FROM products WHERE id=? FOR UPDATE",productId));
        }
        for(var entry:products.entrySet()) {
            UUID productId=entry.getKey(); JsonNode effect=effects.get(productId);
            if(effect==null) throw new ApiException(HttpStatus.CONFLICT,"PURCHASE_REVALUATION_REQUIRED");
            BigDecimal currentStock=(BigDecimal)entry.getValue().get("current_stock");
            BigDecimal currentWac=(BigDecimal)entry.getValue().get("current_wac");
            BigDecimal expectedStock=new BigDecimal(effect.path("newStock").asText());
            BigDecimal expectedWac=new BigDecimal(effect.path("newWac").asText());
            if(currentStock.compareTo(expectedStock)!=0 || currentWac==null || currentWac.compareTo(expectedWac)!=0
                || Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inventory_movements m WHERE m.product_id=? AND m.reference_id<>?
                    AND m.created_at >= (SELECT min(created_at) FROM inventory_movements WHERE reference_type='PURCHASE' AND reference_id=?))
                    """,Boolean.class,productId,id,id))) {
                throw new ApiException(HttpStatus.CONFLICT,"PURCHASE_REVALUATION_REQUIRED");
            }
        }
        for(var entry:products.entrySet()) {
            JsonNode effect=effects.get(entry.getKey());
            BigDecimal oldStock=new BigDecimal(effect.path("oldStock").asText());
            BigDecimal oldWac=effect.path("oldWac").isNull() ? null : new BigDecimal(effect.path("oldWac").asText());
            jdbc.update("UPDATE products SET current_stock=?,current_wac=?,version=version+1 WHERE id=?",oldStock,oldWac,entry.getKey());
        }
        for(var item:items) jdbc.update("""
            INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,unit_cost,notes,created_by)
            VALUES (?,?,'REVERSAL',?,'PURCHASE_CANCEL',?,?,?,?)
            """,UUID.randomUUID(),item.get("product_id"),((BigDecimal)item.get("quantity")).negate(),id,item.get("unit_cost"),request.reason().strip(),user.id());
        jdbc.update("UPDATE purchase_invoices SET status='CANCELLED',version=version+1 WHERE id=?",id);
        jdbc.update("""
            INSERT INTO purchase_cancellations(purchase_invoice_id,reason,refund_received,refund_method_id,refund_method_name,confirmed_by)
            VALUES (?,?,?,?,?,?)
            """,id,request.reason().strip(),paid,method==null ? null : method.get("id"),method==null ? null : method.get("name"),user.id());
        var audit=new LinkedHashMap<String,Object>();
        audit.put("before",invoice); audit.put("reason",request.reason().strip()); audit.put("refundReceived",paid);
        audit.put("refundMethod",method==null ? null : method.get("name")); audit.put("reversedItems",items);
        jdbc.update("INSERT INTO purchase_audit(purchase_invoice_id,user_id,action,new_values) VALUES (?,?,'CANCEL',?::jsonb)",id,user.id(),serialize(audit));
        return Map.of("id",id.toString(),"status","CANCELLED","refundReceived",paid.toPlainString());
    }
    private Map<UUID,JsonNode> creationEffects(UUID id) {
        try {
            String raw=jdbc.queryForObject("SELECT new_values::text FROM purchase_audit WHERE purchase_invoice_id=? AND action IN ('CREATE','EDIT') ORDER BY id DESC LIMIT 1",String.class,id);
            var effects=new HashMap<UUID,JsonNode>();
            for(JsonNode effect:json.readTree(raw).path("inventoryEffects")) effects.put(UUID.fromString(effect.path("productId").asText()),effect);
            return effects;
        } catch(Exception ex) { throw new ApiException(HttpStatus.CONFLICT,"PURCHASE_REVALUATION_REQUIRED"); }
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize cancellation",ex); }
    }
}
