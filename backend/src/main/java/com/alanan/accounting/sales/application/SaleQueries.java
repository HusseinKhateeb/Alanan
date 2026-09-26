package com.alanan.accounting.sales.application;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleQueries {
    private final JdbcTemplate jdbc;
    public SaleQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SUMMARY="SELECT id,invoice_number,customer_id,customer_name,invoice_date,total,paid,(CASE WHEN status='CANCELLED' THEN 0 ELSE total-paid END)::numeric(18,2) outstanding,status,negative_stock FROM sales";
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page list(int page,UUID customerId,String q) {
        PagedRows.validPage(page);
        String search=q.strip();
        if(search.length()>100) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED");
        var sql=new StringBuilder(SUMMARY+" WHERE 1=1");
        var args=new ArrayList<Object>();
        if(customerId!=null) { sql.append(" AND customer_id=?"); args.add(customerId); }
        if(!search.isEmpty()) {
            sql.append(" AND (position(lower(?) in lower(invoice_number))>0 OR position(lower(?) in lower(customer_name))>0 OR position(? in invoice_date::text)>0)");
            args.add(search); args.add(search); args.add(search);
        }
        sql.append(" ORDER BY invoice_date DESC,created_at DESC,id LIMIT 51 OFFSET ?"); args.add((long)page*50);
        return PagedRows.page(jdbc.queryForList(sql.toString(),args.toArray()));
    }
    @Transactional(readOnly=true)
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public Map<String,Object> detail(UUID id,AuthUser user) {
        var rows=jdbc.queryForList(SUMMARY+" WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=PagedRows.external(rows.getFirst());
        result.putAll(PagedRows.external(jdbc.queryForMap("SELECT notes,paid_at_sale,payment_method_id,payment_method_name,created_at,version FROM sales WHERE id=?",id)));
        result.put("customer_is_walk_in", jdbc.queryForObject("SELECT is_walk_in FROM customers WHERE id=?",Boolean.class,rows.getFirst().get("customer_id")));
        String costs=user.permissions().contains("COST_VIEW") ? ",cost_at_sale,cost_total" : "";
        result.put("items",jdbc.queryForList("SELECT product_id,container_type_id,item_name,product_code,item_type,selling_mode,quantity,unit_price,requested_amount,line_total"+costs+" FROM sale_items WHERE sale_id=? ORDER BY line_number",id).stream().map(PagedRows::external).toList());
        result.put("payments",jdbc.queryForList("SELECT cp.id,cp.payment_date,cp.payment_method_name,a.amount,a.active FROM customer_payments cp JOIN customer_payment_allocations a ON a.payment_id=cp.id WHERE a.sale_id=? ORDER BY cp.created_at,cp.id",id).stream().map(PagedRows::external).toList());
        var cancellations=jdbc.queryForList("SELECT reason,refund_amount,refund_method_name,created_at FROM sale_cancellations WHERE sale_id=?",id);
        result.put("cancellation",cancellations.isEmpty() ? null : PagedRows.external(cancellations.getFirst()));
        return result;
    }
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page balances(int page) {
        PagedRows.validPage(page);
        return PagedRows.page(jdbc.queryForList("""
            SELECT c.id,c.name,sum(s.total)::numeric(18,2) sold,sum(s.paid)::numeric(18,2) paid,
            sum(s.total-s.paid)::numeric(18,2) outstanding FROM customers c JOIN sales s ON s.customer_id=c.id
            WHERE NOT c.is_walk_in AND s.status<>'CANCELLED' GROUP BY c.id,c.name ORDER BY lower(c.name),c.id LIMIT 51 OFFSET ?
            """,(long)page*50));
    }
}
