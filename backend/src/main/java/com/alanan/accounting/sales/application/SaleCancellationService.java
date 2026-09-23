package com.alanan.accounting.sales.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.sales.api.CancelSaleRequest;
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
public class SaleCancellationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SaleCancellationService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional
    @PreAuthorize("hasAuthority('SALES_EDIT')")
    public Map<String,Object> cancel(UUID id,CancelSaleRequest request,AuthUser user) {
        var reference=jdbc.queryForList("SELECT customer_id FROM sales WHERE id=?",id);
        if(reference.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        UUID customerId=(UUID)reference.getFirst().get("customer_id");
        // Customer payments lock the customer before the sale; keep that order here.
        jdbc.queryForList("SELECT id FROM customers WHERE id=? FOR UPDATE",customerId);
        var rows=jdbc.queryForList("SELECT id,invoice_number,status,paid,total FROM sales WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var sale=rows.getFirst();
        if("CANCELLED".equals(sale.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        BigDecimal paid=(BigDecimal)sale.get("paid");
        Map<String,Object> method=null;
        if(paid.signum()>0) {
            if(!request.refundConfirmed()) throw new ApiException(HttpStatus.BAD_REQUEST,"REFUND_CONFIRMATION_REQUIRED");
            var methods=request.refundMethodId()==null
                ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
                : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.refundMethodId());
            if(methods.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_METHOD_UNAVAILABLE");
            method=methods.getFirst();
        }
        var items=jdbc.queryForList("SELECT id,product_id,quantity FROM sale_items WHERE sale_id=? AND product_id IS NOT NULL ORDER BY product_id,id",id);
        var products=new TreeMap<UUID,Map<String,Object>>();
        for(var item:items) {
            UUID productId=(UUID)item.get("product_id");
            if(!products.containsKey(productId)) products.put(productId,jdbc.queryForMap("SELECT current_stock,current_wac FROM products WHERE id=? FOR UPDATE",productId));
        }
        var quantities=new TreeMap<UUID,BigDecimal>();
        for(var item:items) quantities.merge((UUID)item.get("product_id"),(BigDecimal)item.get("quantity"),BigDecimal::add);
        for(var entry:quantities.entrySet()) {
            BigDecimal stock=((BigDecimal)products.get(entry.getKey()).get("current_stock")).add(entry.getValue());
            if(stock.precision()>18) throw new ApiException(HttpStatus.BAD_REQUEST,"SALE_AMOUNT_INVALID");
            jdbc.update("UPDATE products SET current_stock=?,version=version+1 WHERE id=?",stock,entry.getKey());
        }
        for(var item:items) {
            UUID productId=(UUID)item.get("product_id");
            jdbc.update("""
                INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,unit_cost,notes,created_by)
                VALUES (?,?,'REVERSAL',?,'SALE_CANCEL',?,?,?,?)
                """,UUID.randomUUID(),productId,item.get("quantity"),id,products.get(productId).get("current_wac"),request.reason().strip(),user.id());
        }
        jdbc.update("UPDATE sales SET status='CANCELLED',version=version+1 WHERE id=?",id);
        jdbc.update("""
            INSERT INTO sale_cancellations(sale_id,reason,refund_amount,refund_method_id,refund_method_name,confirmed_by)
            VALUES (?,?,?,?,?,?)
            """,id,request.reason().strip(),paid,method==null ? null : method.get("id"),method==null ? null : method.get("name"),user.id());
        var audit=new LinkedHashMap<String,Object>();
        audit.put("before",sale); audit.put("reason",request.reason().strip()); audit.put("refundAmount",paid);
        audit.put("refundMethod",method==null ? null : method.get("name")); audit.put("returnedStock",quantities);
        jdbc.update("INSERT INTO sale_audit(sale_id,user_id,action,new_values) VALUES (?,?,'CANCEL',?::jsonb)",id,user.id(),serialize(audit));
        return Map.of("id",id.toString(),"status","CANCELLED","refundAmount",paid.toPlainString());
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize cancellation",ex); }
    }
}
