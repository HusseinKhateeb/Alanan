package com.alanan.accounting.orders;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.sales.api.SaleRequest;
import com.alanan.accounting.sales.application.SaleService;
import com.alanan.accounting.sales.domain.SaleMath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Hebron");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SaleService sales;
    public OrderService(JdbcTemplate jdbc, ObjectMapper json, SaleService sales) {
        this.jdbc=jdbc; this.json=json; this.sales=sales;
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private SaleRequest deserialize(Object value) {
        try { return json.readValue(String.valueOf(value), SaleRequest.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private Map<String,Object> row(UUID id, boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM orders WHERE id=?"+(lock ? " FOR UPDATE" : ""),id);
        if(rows.isEmpty() || "DELETED".equals(rows.getFirst().get("status"))) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        return rows.getFirst();
    }
    private void open(Map<String,Object> row, Long version) {
        if(!"OPEN".equals(row.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"ORDER_ALREADY_DELIVERED");
        if(!Objects.equals(((Number)row.get("version")).longValue(),version)) throw new ApiException(HttpStatus.CONFLICT,"ORDER_VERSION_CONFLICT");
    }
    private record Estimate(UUID customerId,String customerName,BigDecimal total) {}
    private Estimate estimate(SaleRequest request) {
        var customers=request.customerId()==null ? jdbc.queryForList("SELECT id,name,is_walk_in FROM customers WHERE active AND is_walk_in")
            : jdbc.queryForList("SELECT id,name,is_walk_in FROM customers WHERE id=? AND active",request.customerId());
        if(customers.isEmpty()) SaleMath.fail("CUSTOMER_UNAVAILABLE");
        BigDecimal total=BigDecimal.ZERO;
        for(var item:request.items()) {
            if((item.productId()==null)==(item.containerTypeId()==null)) SaleMath.fail("SALE_REFERENCE_REQUIRED");
            boolean weight=false;
            if(item.productId()!=null) {
                var products=jdbc.queryForList("SELECT tracking_type FROM products WHERE id=? AND active",item.productId());
                if(products.isEmpty()) SaleMath.fail("PRODUCT_UNAVAILABLE");
                weight="WEIGHT".equals(products.getFirst().get("tracking_type"));
            } else if(jdbc.queryForList("SELECT id FROM container_types WHERE id=? AND active",item.containerTypeId()).isEmpty()) {
                SaleMath.fail("CONTAINER_UNAVAILABLE");
            }
            total=SaleMath.money(total.add(SaleMath.calculate(item,weight).total()));
        }
        if(request.paidNow().compareTo(total)>0) SaleMath.fail("PAYMENT_EXCEEDS_TOTAL");
        var customer=customers.getFirst();
        if(Boolean.TRUE.equals(customer.get("is_walk_in")) && request.paidNow().compareTo(total)!=0) SaleMath.fail("WALK_IN_MUST_PAY_FULL");
        if(request.paidNow().signum()>0 && request.paymentMethodId()!=null &&
            jdbc.queryForList("SELECT id FROM payment_methods WHERE id=? AND active",request.paymentMethodId()).isEmpty())
            SaleMath.fail("PAYMENT_METHOD_UNAVAILABLE");
        return new Estimate((UUID)customer.get("id"),(String)customer.get("name"),total);
    }
    private Map<String,Object> summary(UUID id) {
        return PagedRows.external(jdbc.queryForMap("SELECT id,order_number,customer_id,customer_name,order_date,notes,total,planned_paid,status,sale_id,version,created_at FROM orders WHERE id=?",id));
    }
    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public Map<String,Object> create(OrderRequest orderRequest,AuthUser user) {
        SaleRequest request=orderRequest.asSaleRequest();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","order:"+user.id()+":"+request.requestId());
        String payload=serialize(request);
        var existing=jdbc.queryForList("SELECT id,request_json=?::jsonb AS same_request FROM orders WHERE created_by=? AND request_id=?",payload,user.id(),request.requestId());
        if(!existing.isEmpty()) {
            if(!Boolean.TRUE.equals(existing.getFirst().get("same_request"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return summary((UUID)existing.getFirst().get("id"));
        }
        var estimated=estimate(request);
        Long number=jdbc.queryForObject("INSERT INTO invoice_counters(kind,invoice_year,next_value) VALUES ('ORDER',?,2) ON CONFLICT(kind,invoice_year) DO UPDATE SET next_value=invoice_counters.next_value+1 RETURNING next_value-1",Long.class,request.invoiceDate().getYear());
        UUID id=UUID.randomUUID();
        String orderNumber="ORD-"+request.invoiceDate().getYear()+"-"+String.format(Locale.ROOT,"%06d",number);
        jdbc.update("INSERT INTO orders(id,order_number,customer_id,customer_name,order_date,notes,total,planned_paid,request_json,request_id,created_by) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)",
            id,orderNumber,estimated.customerId(),estimated.customerName(),request.invoiceDate(),request.notes(),estimated.total(),request.paidNow(),payload,request.requestId(),user.id());
        jdbc.update("INSERT INTO order_events(order_id,user_id,action,details) VALUES (?,?,'CREATE',?::jsonb)",id,user.id(),payload);
        return summary(id);
    }
    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public Map<String,Object> edit(UUID id,OrderChangeRequest change,AuthUser user) {
        var current=row(id,true);
        open(current,change.version());
        SaleRequest revised=change.sale().asSaleRequest();
        var estimated=estimate(revised);
        String payload=serialize(revised);
        jdbc.update("UPDATE orders SET customer_id=?,customer_name=?,order_date=?,notes=?,total=?,planned_paid=?,request_json=?::jsonb,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            estimated.customerId(),estimated.customerName(),revised.invoiceDate(),revised.notes(),estimated.total(),revised.paidNow(),payload,id);
        jdbc.update("INSERT INTO order_events(order_id,user_id,action,details) VALUES (?,?,'EDIT',?::jsonb)",id,user.id(),payload);
        return detail(id);
    }
    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public Map<String,Object> deliver(UUID id,DeliverOrderRequest request,AuthUser user) {
        var current=row(id,true);
        if("DELIVERED".equals(current.get("status"))) return detail(id);
        open(current,request.version());
        if(((java.sql.Date)current.get("order_date")).toLocalDate().isAfter(LocalDate.now(BUSINESS_ZONE)))
            throw new ApiException(HttpStatus.CONFLICT,"ORDER_DELIVERY_NOT_DUE");
        if(request.deliveryDate().isAfter(LocalDate.now(BUSINESS_ZONE)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED");
        var saved=deserialize(current.get("request_json"));
        var invoice=new SaleRequest(id,saved.customerId(),request.deliveryDate(),saved.notes(),saved.paidNow(),saved.paymentMethodId(),saved.items());
        var receipt=sales.create(invoice,user);
        jdbc.update("UPDATE orders SET status='DELIVERED',sale_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(receipt.id()),id);
        jdbc.update("INSERT INTO order_events(order_id,user_id,action,details) VALUES (?,?,'DELIVER',?::jsonb)",id,user.id(),serialize(Map.of("saleId",receipt.id(),"invoiceNumber",receipt.invoiceNumber())));
        return detail(id);
    }
    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public void delete(UUID id,DeleteOrderRequest request,AuthUser user) {
        var current=row(id,true);
        open(current,request.version());
        jdbc.update("UPDATE orders SET status='DELETED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        jdbc.update("INSERT INTO order_events(order_id,user_id,action,reason,details) VALUES (?,?,'DELETE',?,?::jsonb)",id,user.id(),request.reason().strip(),String.valueOf(current.get("request_json")));
    }
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page list(int page,String q) {
        PagedRows.validPage(page);
        String search=q.strip();
        if(search.length()>100) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED");
        return PagedRows.page(jdbc.queryForList("SELECT id,order_number,customer_name,order_date,total,planned_paid,status,sale_id,version FROM orders WHERE status<>'DELETED' AND (position(lower(?) in lower(order_number))>0 OR position(lower(?) in lower(customer_name))>0) ORDER BY created_at DESC,id LIMIT 51 OFFSET ?",
            search,search,(long)page*50));
    }
    @Transactional(readOnly=true)
    @PreAuthorize("hasAuthority('SALES_VIEW') or hasAuthority('SALES_CREATE')")
    public Map<String,Object> detail(UUID id) {
        var current=row(id,false);
        var result=summary(id);
        SaleRequest sale=deserialize(current.get("request_json"));
        result.put("request",sale);
        var references=new ArrayList<Map<String,Object>>();
        for(var item:sale.items()) {
            Map<String,Object> reference=item.productId()!=null
                ? jdbc.queryForMap("SELECT id,name,active,tracking_type,default_selling_price FROM products WHERE id=?",item.productId())
                : jdbc.queryForMap("SELECT id,name,active,default_price FROM container_types WHERE id=?",item.containerTypeId());
            references.add(PagedRows.external(reference));
        }
        result.put("references",references);
        return result;
    }
}
