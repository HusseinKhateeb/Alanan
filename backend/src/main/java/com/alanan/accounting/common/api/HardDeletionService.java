package com.alanan.accounting.common.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.purchases.api.CancelPurchaseRequest;
import com.alanan.accounting.purchases.application.PurchaseCancellationService;
import com.alanan.accounting.sales.api.CancelSaleRequest;
import com.alanan.accounting.sales.application.SaleCancellationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HardDeletionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SaleCancellationService sales;
    private final PurchaseCancellationService purchases;

    public HardDeletionService(JdbcTemplate jdbc, ObjectMapper json, SaleCancellationService sales, PurchaseCancellationService purchases) {
        this.jdbc=jdbc; this.json=json; this.sales=sales; this.purchases=purchases;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(String kind, UUID id, HardDeleteRequest request, AuthUser actor) {
        String reason=request.reason().strip();
        if(reason.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED");
        switch(kind) {
            case "sales" -> invoice(id, reason, actor, false);
            case "purchases" -> invoice(id, reason, actor, true);
            case "customer-payments" -> payment(id, reason, actor, false);
            case "supplier-payments" -> payment(id, reason, actor, true);
            case "expenses" -> expense(id, reason, actor);
            case "waste" -> waste(id, reason, actor);
            case "opening-stock" -> openingStock(id, reason, actor);
            default -> throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
    }

    private void invoice(UUID id, String reason, AuthUser actor, boolean purchase) {
        String table=purchase ? "purchase_invoices" : "sales";
        String account=purchase ? "suppliers" : "customers";
        String accountKey=purchase ? "supplier_id" : "customer_id";
        String items=purchase ? "purchase_items" : "sale_items";
        String itemKey=purchase ? "purchase_invoice_id" : "sale_id";
        String allocations=purchase ? "supplier_payment_allocations" : "customer_payment_allocations";
        String audit=purchase ? "purchase_audit" : "sale_audit";
        String movement=purchase ? "PURCHASE" : "SALE";
        var reference=one("SELECT "+accountKey+" FROM "+table+" WHERE id=?",id);
        jdbc.queryForList("SELECT id FROM "+account+" WHERE id=? FOR UPDATE",reference.get(accountKey));
        var row=one("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",id);
        var lines=jdbc.queryForList("SELECT * FROM "+items+" WHERE "+itemKey+"=? ORDER BY line_number",id);
        if(exists("SELECT EXISTS(SELECT 1 FROM "+allocations+" WHERE "+itemKey+"=?)",id)) conflict();
        var products=new TreeSet<UUID>();
        for(var line:lines) if(line.get("product_id")!=null) products.add((UUID)line.get("product_id"));
        for(UUID product:products) jdbc.queryForList("SELECT id FROM products WHERE id=? FOR UPDATE",product);
        for(UUID product:products) {
            if(exists("""
                SELECT EXISTS(SELECT 1 FROM inventory_movements m WHERE m.product_id=? AND m.reference_id<>?
                    AND m.created_at >= (SELECT min(created_at) FROM inventory_movements WHERE product_id=? AND reference_type=? AND reference_id=?))
                """,product,id,product,movement,id)) conflict();
        }
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("record",row); snapshot.put("items",lines);
        snapshot.put("audit",jdbc.queryForList("SELECT action,new_values::text,created_at FROM "+audit+" WHERE "+itemKey+"=? ORDER BY id",id));
        if(!"CANCELLED".equals(row.get("status"))) {
            BigDecimal paid=purchase
                ? jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM supplier_payment_allocations WHERE purchase_invoice_id=? AND active",BigDecimal.class,id)
                : (BigDecimal)row.get("paid");
            if(paid.signum()>0) throw new ApiException(HttpStatus.CONFLICT,"DELETE_REFUND_REQUIRED");
            if(purchase) purchases.reverseForHardDeletion(id,new CancelPurchaseRequest(reason,false,null),actor);
            else sales.reverseForHardDeletion(id,new CancelSaleRequest(reason,false,null),actor);
        }
        archive(purchase ? "PURCHASE" : "SALE",id,(String)row.get("invoice_number"),reason,actor,snapshot);
        jdbc.update("DELETE FROM inventory_movements WHERE reference_id=? AND reference_type IN (?,?)",id,movement,purchase ? "PURCHASE_CANCEL" : "SALE_CANCEL");
        jdbc.update("DELETE FROM "+audit+" WHERE "+itemKey+"=?",id);
        jdbc.update("DELETE FROM "+(purchase ? "purchase_cancellations WHERE purchase_invoice_id" : "sale_cancellations WHERE sale_id")+"=?",id);
        jdbc.update("DELETE FROM "+items+" WHERE "+itemKey+"=?",id);
        jdbc.update("DELETE FROM "+table+" WHERE id=?",id);
    }

    private void payment(UUID id, String reason, AuthUser actor, boolean supplier) {
        String table=supplier ? "supplier_payments" : "customer_payments";
        String account=supplier ? "suppliers" : "customers";
        String accountKey=supplier ? "supplier_id" : "customer_id";
        String allocations=supplier ? "supplier_payment_allocations" : "customer_payment_allocations";
        String audit=supplier ? "supplier_payment_audit" : "customer_payment_audit";
        String cancelled=supplier ? "supplier_payment_cancellations" : "customer_payment_cancellations";
        var reference=one("SELECT "+accountKey+" FROM "+table+" WHERE id=?",id);
        jdbc.queryForList("SELECT id FROM "+account+" WHERE id=? FOR UPDATE",reference.get(accountKey));
        var row=one("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",id);
        var linked=jdbc.queryForList("SELECT * FROM "+allocations+" WHERE payment_id=?",id);
        if(!exists("SELECT EXISTS(SELECT 1 FROM "+cancelled+" WHERE payment_id=?)",id))
            throw new ApiException(HttpStatus.CONFLICT,"DELETE_REFUND_REQUIRED");
        if(linked.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("active")))) conflict();
        var snapshot=Map.of("record",row,"allocations",linked,"audit",jdbc.queryForList("SELECT action,new_values::text,created_at FROM "+audit+" WHERE payment_id=? ORDER BY id",id));
        archive(supplier ? "SUPPLIER_PAYMENT" : "CUSTOMER_PAYMENT",id,null,reason,actor,snapshot);
        jdbc.update("DELETE FROM "+allocations+" WHERE payment_id=?",id);
        jdbc.update("DELETE FROM "+cancelled+" WHERE payment_id=?",id);
        jdbc.update("DELETE FROM "+audit+" WHERE payment_id=?",id);
        jdbc.update("DELETE FROM "+table+" WHERE id=?",id);
    }

    private void expense(UUID id, String reason, AuthUser actor) {
        var row=one("SELECT * FROM expenses WHERE id=? FOR UPDATE",id);
        archive("EXPENSE",id,null,reason,actor,Map.of("record",row,"audit",jdbc.queryForList("SELECT action,new_values::text,created_at FROM expense_audit WHERE expense_id=? ORDER BY id",id)));
        jdbc.update("DELETE FROM expense_cancellations WHERE expense_id=?",id);
        jdbc.update("DELETE FROM expense_audit WHERE expense_id=?",id);
        jdbc.update("DELETE FROM expenses WHERE id=?",id);
    }

    private void waste(UUID id, String reason, AuthUser actor) {
        var row=one("SELECT * FROM waste_records WHERE id=? FOR UPDATE",id);
        UUID product=(UUID)row.get("product_id");
        jdbc.queryForList("SELECT id FROM products WHERE id=? FOR UPDATE",product);
        latestMovement(product,id);
        archive("WASTE",id,null,reason,actor,Map.of("record",row,"audit",jdbc.queryForList("SELECT action,new_values::text,created_at FROM waste_audit WHERE waste_id=? ORDER BY id",id)));
        jdbc.update("UPDATE products SET current_stock=current_stock+?,version=version+1 WHERE id=?",row.get("quantity"),product);
        jdbc.update("DELETE FROM inventory_movements WHERE reference_type='WASTE' AND reference_id=?",id);
        jdbc.update("DELETE FROM waste_audit WHERE waste_id=?",id);
        jdbc.update("DELETE FROM waste_records WHERE id=?",id);
    }

    private void openingStock(UUID id, String reason, AuthUser actor) {
        var row=one("SELECT * FROM inventory_adjustments WHERE id=? FOR UPDATE",id);
        UUID product=(UUID)row.get("product_id");
        var current=one("SELECT current_stock,current_wac FROM products WHERE id=? FOR UPDATE",product);
        latestMovement(product,id);
        if(((BigDecimal)current.get("current_stock")).compareTo((BigDecimal)row.get("new_stock"))!=0
            || ((BigDecimal)current.get("current_wac")).compareTo((BigDecimal)row.get("new_wac"))!=0) conflict();
        archive("INVENTORY_ADJUSTMENT",id,null,reason,actor,Map.of("record",row,"audit",jdbc.queryForList("SELECT action,new_values::text,created_at FROM inventory_adjustment_audit WHERE adjustment_id=? ORDER BY id",id)));
        jdbc.update("UPDATE products SET current_stock=?,current_wac=?,version=version+1 WHERE id=?",row.get("old_stock"),row.get("old_wac"),product);
        jdbc.update("DELETE FROM inventory_movements WHERE reference_type='OPENING_STOCK' AND reference_id=?",id);
        jdbc.update("DELETE FROM inventory_adjustment_audit WHERE adjustment_id=?",id);
        jdbc.update("DELETE FROM inventory_adjustments WHERE id=?",id);
    }

    private void latestMovement(UUID product, UUID id) {
        if(exists("""
            SELECT EXISTS(SELECT 1 FROM inventory_movements m WHERE m.product_id=? AND m.reference_id<>?
                AND m.created_at >= (SELECT min(created_at) FROM inventory_movements WHERE product_id=? AND reference_id=?))
            """,product,id,product,id)) conflict();
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        return rows.getFirst();
    }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private void conflict() { throw new ApiException(HttpStatus.CONFLICT,"DELETE_DEPENDENCIES"); }
    private void archive(String type,UUID id,String reference,String reason,AuthUser actor,Object snapshot) {
        try {
            jdbc.update("INSERT INTO hard_deletion_audit(entity_type,entity_id,reference,reason,deleted_by,snapshot) VALUES (?,?,?,?,?,?::jsonb)",
                type,id,reference,reason,actor.id(),json.writeValueAsString(snapshot));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize deletion audit",ex); }
    }
}
