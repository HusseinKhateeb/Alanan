package com.alanan.accounting.sales.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.sales.api.CorrectSaleRequest;
import com.alanan.accounting.sales.api.SaleRequest;
import com.alanan.accounting.sales.domain.SaleMath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleCorrectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private record Line(SaleRequest.Item input, Map<String,Object> reference, SaleMath.Line amount,
                        String type, BigDecimal cost) {}

    public SaleCorrectionService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional
    @PreAuthorize("hasAuthority('SALES_EDIT')")
    public Map<String,Object> correct(UUID id, CorrectSaleRequest request, AuthUser actor) {
        var refs=jdbc.queryForList("SELECT customer_id FROM sales WHERE id=?",id);
        if(refs.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        UUID oldCustomer=(UUID)refs.getFirst().get("customer_id");
        UUID newCustomer=request.customerId();
        if(newCustomer==null) {
            newCustomer=jdbc.queryForObject("SELECT id FROM customers WHERE is_walk_in AND active",UUID.class);
            if(newCustomer==null) throw new ApiException(HttpStatus.BAD_REQUEST,"CUSTOMER_UNAVAILABLE");
        }
        for(UUID customer:new TreeSet<>(List.of(oldCustomer,newCustomer)))
            jdbc.queryForList("SELECT id FROM customers WHERE id=? FOR UPDATE",customer);
        var rows=jdbc.queryForList("SELECT * FROM sales WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var sale=rows.getFirst();
        if(!oldCustomer.equals(sale.get("customer_id"))) throw new ApiException(HttpStatus.CONFLICT,"EDIT_CONFLICT");
        if("CANCELLED".equals(sale.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        if(((Number)sale.get("version")).longValue()!=request.version()) throw new ApiException(HttpStatus.CONFLICT,"EDIT_CONFLICT");
        LocalDate oldDate=((java.sql.Date)sale.get("invoice_date")).toLocalDate();
        if(request.invoiceDate().getYear()!=oldDate.getYear()) throw new ApiException(HttpStatus.BAD_REQUEST,"INVOICE_YEAR_LOCKED");
        var customers=jdbc.queryForList("SELECT id,name,is_walk_in,active FROM customers WHERE id=? FOR SHARE",newCustomer);
        if(customers.isEmpty() || (!newCustomer.equals(oldCustomer) && !Boolean.TRUE.equals(customers.getFirst().get("active"))))
            throw new ApiException(HttpStatus.BAD_REQUEST,"CUSTOMER_UNAVAILABLE");
        var customer=customers.getFirst();
        var original=jdbc.queryForList("SELECT * FROM sale_items WHERE sale_id=? ORDER BY line_number",id);
        var allocations=jdbc.queryForList("SELECT * FROM customer_payment_allocations WHERE sale_id=?",id);
        if(!oldCustomer.equals(newCustomer) && !allocations.isEmpty())
            throw new ApiException(HttpStatus.CONFLICT,"SALE_CUSTOMER_PAYMENTS_LINKED");
        BigDecimal laterPaid=allocations.stream().filter(a -> Boolean.TRUE.equals(a.get("active")))
            .map(a -> (BigDecimal)a.get("amount")).reduce(BigDecimal.ZERO,BigDecimal::add);

        var productIds=new TreeSet<UUID>();
        var containerIds=new TreeSet<UUID>();
        var originalContainerIds=new HashSet<UUID>();
        var oldQuantities=new TreeMap<UUID,BigDecimal>();
        var oldCosts=new HashMap<UUID,BigDecimal>();
        for(var item:original) {
            if(item.get("product_id") instanceof UUID product) {
                productIds.add(product);
                oldQuantities.merge(product,(BigDecimal)item.get("quantity"),BigDecimal::add);
                oldCosts.putIfAbsent(product,(BigDecimal)item.get("cost_at_sale"));
            } else if(item.get("container_type_id") instanceof UUID container) originalContainerIds.add(container);
        }
        for(var item:request.items()) {
            if((item.productId()==null)==(item.containerTypeId()==null)) SaleMath.fail("SALE_REFERENCE_REQUIRED");
            if(item.productId()!=null) productIds.add(item.productId());
            else containerIds.add(item.containerTypeId());
        }
        var products=new TreeMap<UUID,Map<String,Object>>();
        for(UUID product:productIds) {
            var found=jdbc.queryForList("SELECT id,name,code,tracking_type,current_stock,current_wac,active FROM products WHERE id=? FOR UPDATE",product);
            if(found.isEmpty() || (!oldQuantities.containsKey(product) && !Boolean.TRUE.equals(found.getFirst().get("active"))))
                SaleMath.fail("PRODUCT_UNAVAILABLE");
            products.put(product,found.getFirst());
        }
        var containers=new TreeMap<UUID,Map<String,Object>>();
        for(UUID container:containerIds) {
            var found=jdbc.queryForList("SELECT id,name,active FROM container_types WHERE id=? FOR SHARE",container);
            if(found.isEmpty() || (!originalContainerIds.contains(container) && !Boolean.TRUE.equals(found.getFirst().get("active"))))
                SaleMath.fail("CONTAINER_UNAVAILABLE");
            containers.put(container,found.getFirst());
        }
        var plan=new ArrayList<Line>();
        var newQuantities=new TreeMap<UUID,BigDecimal>();
        BigDecimal total=BigDecimal.ZERO;
        for(var item:request.items()) {
            boolean container=item.containerTypeId()!=null;
            var reference=container ? containers.get(item.containerTypeId()) : products.get(item.productId());
            boolean weight=!container && "WEIGHT".equals(reference.get("tracking_type"));
            var amount=SaleMath.calculate(item,weight);
            BigDecimal cost=container ? null : oldQuantities.containsKey(item.productId())
                ? oldCosts.get(item.productId()) : (BigDecimal)reference.get("current_wac");
            plan.add(new Line(item,reference,amount,container ? "SPICE_CONTAINER" : weight ? "WEIGHT_PRODUCT" : "PIECE_PRODUCT",cost));
            total=SaleMath.money(total.add(amount.total()));
            if(!container) newQuantities.merge(item.productId(),amount.quantity(),BigDecimal::add);
        }
        BigDecimal paid=SaleMath.money(request.paidNow().add(laterPaid));
        if(paid.compareTo(total)>0) SaleMath.fail("PAYMENT_EXCEEDS_TOTAL");
        if(Boolean.TRUE.equals(customer.get("is_walk_in")) && paid.compareTo(total)!=0) SaleMath.fail("WALK_IN_MUST_PAY_FULL");
        Map<String,Object> method=null;
        if(request.paidNow().signum()>0) {
            var methods=request.paymentMethodId()==null
                ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
                : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE id=? AND (active OR id=?) FOR SHARE",
                    request.paymentMethodId(),sale.get("payment_method_id"));
            if(methods.isEmpty()) SaleMath.fail("PAYMENT_METHOD_UNAVAILABLE");
            method=methods.getFirst();
        }
        boolean negative=Boolean.TRUE.equals(sale.get("negative_stock"));
        Timestamp created=(Timestamp)sale.get("created_at");
        for(UUID product:productIds) {
            BigDecimal before=oldQuantities.getOrDefault(product,BigDecimal.ZERO);
            BigDecimal after=newQuantities.getOrDefault(product,BigDecimal.ZERO);
            BigDecimal stock=(BigDecimal)products.get(product).get("current_stock");
            if(before.compareTo(after)!=0) {
                boolean later=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inventory_movements WHERE product_id=? AND reference_id<>? AND created_at>=?)
                    """,Boolean.class,product,id,created));
                if(later) throw new ApiException(HttpStatus.CONFLICT,"SALE_STOCK_REPLAY_REQUIRED");
                stock=stock.add(before).subtract(after);
                if(stock.precision()>18) SaleMath.fail("SALE_AMOUNT_INVALID");
                jdbc.update("UPDATE products SET current_stock=?,version=version+1 WHERE id=?",stock,product);
            }
            if(after.signum()>0 && stock.signum()<0) negative=true;
        }
        for(int index=0;index<Math.max(original.size(),plan.size());index++) {
            Map<String,Object> old=index<original.size() ? original.get(index) : null;
            Line changed=index<plan.size() ? plan.get(index) : null;
            UUID itemId=old==null ? UUID.randomUUID() : (UUID)old.get("id");
            if(changed==null) {
                jdbc.update("DELETE FROM inventory_movements WHERE sale_item_id=?",itemId);
                jdbc.update("DELETE FROM sale_items WHERE id=?",itemId);
                continue;
            }
            UUID product=changed.input().productId();
            BigDecimal costTotal=changed.cost()==null ? null : SaleMath.money(changed.cost().multiply(changed.amount().quantity()));
            boolean sameReference=old!=null && Objects.equals(old.get("product_id"),product)
                && Objects.equals(old.get("container_type_id"),changed.input().containerTypeId());
            Object name=sameReference ? old.get("item_name") : changed.reference().get("name");
            Object code=product==null ? null : sameReference ? old.get("product_code") : changed.reference().get("code");
            if(old==null) jdbc.update("""
                INSERT INTO sale_items(id,sale_id,line_number,product_id,container_type_id,item_name,product_code,item_type,selling_mode,quantity,unit_price,requested_amount,line_total,cost_at_sale,cost_total)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,itemId,id,index+1,product,changed.input().containerTypeId(),name,code,changed.type(),changed.input().mode().name(),changed.amount().quantity(),changed.input().unitPrice(),changed.input().requestedAmount(),changed.amount().total(),changed.cost(),costTotal);
            else jdbc.update("""
                UPDATE sale_items SET product_id=?,container_type_id=?,item_name=?,product_code=?,item_type=?,selling_mode=?,quantity=?,unit_price=?,requested_amount=?,line_total=?,cost_at_sale=?,cost_total=? WHERE id=?
                """,product,changed.input().containerTypeId(),name,code,changed.type(),changed.input().mode().name(),changed.amount().quantity(),changed.input().unitPrice(),changed.input().requestedAmount(),changed.amount().total(),changed.cost(),costTotal,itemId);
            var movement=jdbc.queryForList("SELECT id FROM inventory_movements WHERE sale_item_id=?",itemId);
            if(product==null) {
                if(!movement.isEmpty()) jdbc.update("DELETE FROM inventory_movements WHERE sale_item_id=?",itemId);
            } else if(movement.isEmpty()) jdbc.update("""
                INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,sale_item_id,unit_cost,notes,created_by,created_at)
                VALUES (?,?,'SALE',?,'SALE',?,?,?,?,?,?,?)
                """,UUID.randomUUID(),product,changed.amount().quantity().negate(),id,itemId,changed.cost(),request.notes(),actor.id(),created);
            else jdbc.update("UPDATE inventory_movements SET product_id=?,quantity_change=?,unit_cost=?,notes=? WHERE sale_item_id=?",
                product,changed.amount().quantity().negate(),changed.cost(),request.notes(),itemId);
        }
        String status=paid.signum()==0 ? "OPEN" : paid.compareTo(total)==0 ? "PAID" : "PARTIAL";
        jdbc.update("""
            UPDATE sales SET customer_id=?,customer_name=?,invoice_date=?,notes=?,total=?,paid=?,paid_at_sale=?,payment_method_id=?,payment_method_name=?,status=?,negative_stock=?,version=version+1 WHERE id=?
            """,newCustomer,newCustomer.equals(oldCustomer) ? sale.get("customer_name") : customer.get("name"),request.invoiceDate(),request.notes(),total,paid,request.paidNow(),method==null ? null : method.get("id"),method==null ? null : Objects.equals(method.get("id"),sale.get("payment_method_id")) ? sale.get("payment_method_name") : method.get("name"),status,negative,id);
        var audit=new LinkedHashMap<String,Object>();
        audit.put("before",sale); audit.put("beforeItems",original); audit.put("after",request); audit.put("reason",request.reason().strip());
        try {
            jdbc.update("INSERT INTO sale_audit(sale_id,user_id,action,new_values) VALUES (?,?,'EDIT',?::jsonb)",id,actor.id(),json.writeValueAsString(audit));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize sale correction",ex); }
        return Map.of("id",id.toString(),"invoiceNumber",sale.get("invoice_number"),"total",total.toPlainString(),
            "paid",paid.toPlainString(),"outstanding",total.subtract(paid).toPlainString(),"status",status);
    }
}
