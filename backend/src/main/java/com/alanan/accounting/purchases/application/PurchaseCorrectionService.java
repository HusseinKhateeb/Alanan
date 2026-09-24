package com.alanan.accounting.purchases.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.purchases.api.CorrectPurchaseRequest;
import com.alanan.accounting.purchases.domain.PurchaseMath;
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
public class PurchaseCorrectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PurchaseCorrectionService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional
    @PreAuthorize("hasAuthority('PURCHASES_CREATE') and hasAuthority('AUDIT_VIEW')")
    public PurchaseService.Receipt correct(UUID id, CorrectPurchaseRequest request, AuthUser user) {
        var reference=jdbc.queryForList("SELECT supplier_id FROM purchase_invoices WHERE id=?",id);
        if(reference.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        jdbc.queryForList("SELECT id FROM suppliers WHERE id=? FOR UPDATE",reference.getFirst().get("supplier_id"));
        var invoice=jdbc.queryForMap("SELECT id,invoice_number,invoice_date,supplier_reference,notes,total,status,version FROM purchase_invoices WHERE id=? FOR UPDATE",id);
        if("CANCELLED".equals(invoice.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        if(((Number)invoice.get("version")).longValue()!=request.version()) throw new ApiException(HttpStatus.CONFLICT,"EDIT_CONFLICT");
        if(request.invoiceDate().getYear()!=((java.sql.Date)invoice.get("invoice_date")).toLocalDate().getYear())
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVOICE_YEAR_LOCKED");
        var items=jdbc.queryForList("SELECT id,product_id,quantity,unit_cost,line_total,tracking_type FROM purchase_items WHERE purchase_invoice_id=? ORDER BY line_number",id);
        if(items.size()!=request.items().size()) throw new ApiException(HttpStatus.CONFLICT,"PURCHASE_LINES_LOCKED");
        var products=new TreeMap<UUID,Map<String,Object>>();
        for(var item:items) {
            UUID productId=(UUID)item.get("product_id");
            if(!products.containsKey(productId)) products.put(productId,jdbc.queryForMap("SELECT current_stock,current_wac FROM products WHERE id=? FOR UPDATE",productId));
        }
        var effects=latestEffects(id);
        for(var entry:products.entrySet()) {
            JsonNode effect=effects.get(entry.getKey());
            if(effect==null || effect.path("newStock").isMissingNode() || effect.path("newWac").isMissingNode()) locked();
            BigDecimal stock=(BigDecimal)entry.getValue().get("current_stock"), wac=(BigDecimal)entry.getValue().get("current_wac");
            if(stock.compareTo(new BigDecimal(effect.path("newStock").asText()))!=0 || wac==null ||
                wac.compareTo(new BigDecimal(effect.path("newWac").asText()))!=0 ||
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inventory_movements m WHERE m.product_id=? AND m.reference_id<>?
                    AND m.created_at >= (SELECT min(created_at) FROM inventory_movements WHERE reference_type='PURCHASE' AND reference_id=?))
                    """,Boolean.class,entry.getKey(),id,id))) locked();
        }
        BigDecimal total=BigDecimal.ZERO;
        var quantities=new HashMap<UUID,BigDecimal>(); var values=new HashMap<UUID,BigDecimal>();
        for(int i=0;i<items.size();i++) {
            var original=items.get(i); var changed=request.items().get(i);
            if("PIECE".equals(original.get("tracking_type")) && changed.quantity().stripTrailingZeros().scale()>0) PurchaseMath.fail("WHOLE_PIECES_REQUIRED");
            total=total.add(PurchaseMath.lineTotal(changed.quantity(),changed.unitCost()));
            UUID productId=(UUID)original.get("product_id");
            quantities.merge(productId,changed.quantity(),BigDecimal::add);
            values.merge(productId,changed.quantity().multiply(changed.unitCost()),BigDecimal::add);
        }
        total=PurchaseMath.money(total);
        if(total.precision()>18) PurchaseMath.fail("PURCHASE_AMOUNT_INVALID");
        BigDecimal paid=jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM supplier_payment_allocations WHERE purchase_invoice_id=? AND active",BigDecimal.class,id);
        if(paid.compareTo(total)>0) PurchaseMath.fail("PAYMENT_EXCEEDS_TOTAL");
        var nextEffects=new ArrayList<Map<String,Object>>();
        for(var entry:products.entrySet()) {
            UUID productId=entry.getKey(); JsonNode previous=effects.get(productId);
            BigDecimal oldStock=new BigDecimal(previous.path("oldStock").asText());
            BigDecimal oldWac=previous.path("oldWac").isNull() ? null : new BigDecimal(previous.path("oldWac").asText());
            BigDecimal incoming=quantities.get(productId), newStock=oldStock.add(incoming);
            if(newStock.precision()>18) PurchaseMath.fail("PURCHASE_AMOUNT_INVALID");
            BigDecimal newWac=PurchaseMath.wac(oldStock,oldWac,incoming,values.get(productId));
            jdbc.update("UPDATE products SET current_stock=?,current_wac=?,version=version+1 WHERE id=?",newStock,newWac,productId);
            var nextEffect=new LinkedHashMap<String,Object>();
            nextEffect.put("productId",productId); nextEffect.put("oldStock",oldStock); nextEffect.put("oldWac",oldWac);
            nextEffect.put("newStock",newStock); nextEffect.put("newWac",newWac); nextEffects.add(nextEffect);
        }
        for(int i=0;i<items.size();i++) {
            var original=items.get(i); var changed=request.items().get(i);
            UUID productId=(UUID)original.get("product_id");
            BigDecimal difference=changed.quantity().subtract((BigDecimal)original.get("quantity"));
            if(difference.signum()!=0) jdbc.update("""
                INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,unit_cost,notes,created_by)
                VALUES (?,?,'CORRECTION',?,'PURCHASE_EDIT',?,?,?,?)
                """,UUID.randomUUID(),productId,difference,id,changed.unitCost(),request.reason().strip(),user.id());
            jdbc.update("UPDATE purchase_items SET quantity=?,unit_cost=?,line_total=? WHERE id=?",changed.quantity(),changed.unitCost(),
                PurchaseMath.lineTotal(changed.quantity(),changed.unitCost()),original.get("id"));
        }
        String status=paid.signum()==0 ? "OPEN" : paid.compareTo(total)==0 ? "PAID" : "PARTIAL";
        jdbc.update("UPDATE purchase_invoices SET invoice_date=?,supplier_reference=?,notes=?,total=?,status=?,version=version+1 WHERE id=?",
            request.invoiceDate(),request.supplierReference(),request.notes(),total,status,id);
        try {
            jdbc.update("INSERT INTO purchase_audit(purchase_invoice_id,user_id,action,new_values) VALUES (?,?,'EDIT',?::jsonb)",id,user.id(),
                json.writeValueAsString(Map.of("before",invoice,"beforeItems",items,"reason",request.reason().strip(),
                    "after",request,"inventoryEffects",nextEffects)));
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return jdbc.queryForObject("""
            SELECT id,invoice_number,total,status FROM purchase_invoices WHERE id=?
            """,(rs,row)->new PurchaseService.Receipt(rs.getString("id"),rs.getString("invoice_number"),rs.getBigDecimal("total").toPlainString(),
                PurchaseMath.money(paid).toPlainString(),rs.getBigDecimal("total").subtract(paid).toPlainString(),rs.getString("status")),id);
    }
    private Map<UUID,JsonNode> latestEffects(UUID id) {
        try {
            String raw=jdbc.queryForObject("SELECT new_values::text FROM purchase_audit WHERE purchase_invoice_id=? AND action IN ('CREATE','EDIT') ORDER BY id DESC LIMIT 1",String.class,id);
            var effects=new HashMap<UUID,JsonNode>();
            for(JsonNode effect:json.readTree(raw).path("inventoryEffects")) effects.put(UUID.fromString(effect.path("productId").asText()),effect);
            return effects;
        } catch(Exception ex) { locked(); return Map.of(); }
    }
    private static void locked() { throw new ApiException(HttpStatus.CONFLICT,"PURCHASE_REVALUATION_REQUIRED"); }
}
