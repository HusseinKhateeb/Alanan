package com.alanan.accounting.sales.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.sales.api.SaleRequest;
import com.alanan.accounting.sales.domain.SaleMath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SaleService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public record Receipt(String id,String invoiceNumber,String total,String paid,String outstanding,String status,boolean negativeStock) {}
    private record Planned(SaleRequest.Item input,Map<String,Object> reference,SaleMath.Line math,String type,BigDecimal cost,BigDecimal costTotal) {}

    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public Receipt create(SaleRequest request,AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","sale:"+user.id()+":"+request.requestId());
        String hash=fingerprint(request);
        var previous=jdbc.queryForList("SELECT id,request_hash FROM sales WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return receipt((UUID)previous.getFirst().get("id"));
        }
        var customers=request.customerId()==null ? jdbc.queryForList("SELECT id,name,is_walk_in FROM customers WHERE is_walk_in AND active FOR SHARE")
            : jdbc.queryForList("SELECT id,name,is_walk_in FROM customers WHERE id=? AND active FOR SHARE",request.customerId());
        if(customers.isEmpty()) SaleMath.fail("CUSTOMER_UNAVAILABLE");
        var customer=customers.getFirst();
        Map<UUID,Map<String,Object>> products=new TreeMap<>(), containers=new TreeMap<>();
        for(var item:request.items()) if((item.productId()==null)==(item.containerTypeId()==null)) SaleMath.fail("SALE_REFERENCE_REQUIRED");
        request.items().stream().map(SaleRequest.Item::productId).filter(Objects::nonNull).distinct().sorted().forEach(id->{
            var rows=jdbc.queryForList("SELECT id,name,code,tracking_type,current_stock,current_wac FROM products WHERE id=? AND active FOR UPDATE",id);
            if(rows.isEmpty()) SaleMath.fail("PRODUCT_UNAVAILABLE"); products.put(id,rows.getFirst());
        });
        request.items().stream().map(SaleRequest.Item::containerTypeId).filter(Objects::nonNull).distinct().sorted().forEach(id->{
            var rows=jdbc.queryForList("SELECT id,name FROM container_types WHERE id=? AND active FOR SHARE",id);
            if(rows.isEmpty()) SaleMath.fail("CONTAINER_UNAVAILABLE"); containers.put(id,rows.getFirst());
        });
        var plan=new ArrayList<Planned>();
        Map<UUID,BigDecimal> quantities=new TreeMap<>();
        BigDecimal total=BigDecimal.ZERO;
        for(var item:request.items()) {
            boolean container=item.containerTypeId()!=null;
            var reference=container ? containers.get(item.containerTypeId()) : products.get(item.productId());
            boolean weight=!container && "WEIGHT".equals(reference.get("tracking_type"));
            var math=SaleMath.calculate(item,weight);
            BigDecimal cost=container ? null : (BigDecimal)reference.get("current_wac");
            plan.add(new Planned(item,reference,math,container ? "SPICE_CONTAINER" : weight ? "WEIGHT_PRODUCT" : "PIECE_PRODUCT",cost,
                cost==null ? null : SaleMath.money(cost.multiply(math.quantity()))));
            total=SaleMath.money(total.add(math.total()));
            if(!container) quantities.merge(item.productId(),math.quantity(),BigDecimal::add);
        }
        if(request.paidNow().compareTo(total)>0) SaleMath.fail("PAYMENT_EXCEEDS_TOTAL");
        if(Boolean.TRUE.equals(customer.get("is_walk_in")) && request.paidNow().compareTo(total)!=0) SaleMath.fail("WALK_IN_MUST_PAY_FULL");
        Map<String,Object> method=null;
        if(request.paidNow().signum()>0) {
            var rows=request.paymentMethodId()==null ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
                : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.paymentMethodId());
            if(rows.isEmpty()) SaleMath.fail("PAYMENT_METHOD_UNAVAILABLE"); method=rows.getFirst();
        }
        boolean negative=false;
        var effects=new ArrayList<Map<String,Object>>();
        for(var entry:quantities.entrySet()) {
            BigDecimal before=(BigDecimal)products.get(entry.getKey()).get("current_stock"), after=before.subtract(entry.getValue());
            if(after.precision()>18) SaleMath.fail("SALE_AMOUNT_INVALID");
            negative|=after.signum()<0;
            jdbc.update("UPDATE products SET current_stock=?,version=version+1 WHERE id=?",after,entry.getKey());
            effects.add(Map.of("productId",entry.getKey(),"oldStock",before,"newStock",after));
        }
        Long number=jdbc.queryForObject("INSERT INTO invoice_counters(kind,invoice_year,next_value) VALUES ('SALE',?,2) ON CONFLICT(kind,invoice_year) DO UPDATE SET next_value=invoice_counters.next_value+1 RETURNING next_value-1",Long.class,request.invoiceDate().getYear());
        String invoiceNumber="SAL-"+request.invoiceDate().getYear()+"-"+String.format(Locale.ROOT,"%06d",number);
        UUID id=UUID.randomUUID();
        String status=request.paidNow().signum()==0 ? "OPEN" : request.paidNow().compareTo(total)==0 ? "PAID" : "PARTIAL";
        jdbc.update("INSERT INTO sales(id,invoice_number,customer_id,customer_name,invoice_date,notes,total,paid,paid_at_sale,payment_method_id,payment_method_name,status,negative_stock,created_by,request_id,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,invoiceNumber,customer.get("id"),customer.get("name"),request.invoiceDate(),request.notes(),total,request.paidNow(),request.paidNow(),method==null ? null : method.get("id"),method==null ? null : method.get("name"),status,negative,user.id(),request.requestId(),hash);
        int line=0;
        for(var p:plan) {
            UUID itemId=UUID.randomUUID();
            jdbc.update("INSERT INTO sale_items(id,sale_id,line_number,product_id,container_type_id,item_name,product_code,item_type,selling_mode,quantity,unit_price,requested_amount,line_total,cost_at_sale,cost_total) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                itemId,id,++line,p.input().productId(),p.input().containerTypeId(),p.reference().get("name"),p.reference().get("code"),p.type(),p.input().mode().name(),p.math().quantity(),p.input().unitPrice(),p.input().requestedAmount(),p.math().total(),p.cost(),p.costTotal());
            if(p.input().productId()!=null) jdbc.update("INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,sale_item_id,unit_cost,notes,created_by) VALUES (?,?,'SALE',?,'SALE',?,?,?,?,?)",
                UUID.randomUUID(),p.input().productId(),p.math().quantity().negate(),id,itemId,p.cost(),request.notes(),user.id());
        }
        jdbc.update("INSERT INTO sale_audit(sale_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",id,user.id(),serialize(Map.of("request",request,"inventoryEffects",effects,"negativeStock",negative)));
        return receipt(id);
    }
    private Receipt receipt(UUID id) {
        return jdbc.queryForObject("SELECT id,invoice_number,total,paid,total-paid outstanding,status,negative_stock FROM sales WHERE id=?",
            (rs,row)->new Receipt(rs.getString("id"),rs.getString("invoice_number"),rs.getBigDecimal("total").toPlainString(),rs.getBigDecimal("paid").toPlainString(),rs.getBigDecimal("outstanding").toPlainString(),rs.getString("status"),rs.getBoolean("negative_stock")),id);
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize sale",ex); }
    }
    private String fingerprint(SaleRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(request).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
