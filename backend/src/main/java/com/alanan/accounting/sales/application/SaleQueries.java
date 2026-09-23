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
    private static final String SUMMARY="SELECT id,invoice_number,customer_id,customer_name,invoice_date,total,paid,total-paid outstanding,status,negative_stock FROM sales";
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page list(int page,UUID customerId) {
        PagedRows.validPage(page);
        return PagedRows.page(customerId==null ? jdbc.queryForList(SUMMARY+" ORDER BY invoice_date DESC,created_at DESC,id LIMIT 51 OFFSET ?",(long)page*50)
            : jdbc.queryForList(SUMMARY+" WHERE customer_id=? ORDER BY invoice_date DESC,created_at DESC,id LIMIT 51 OFFSET ?",customerId,(long)page*50));
    }
    @Transactional(readOnly=true)
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public Map<String,Object> detail(UUID id,AuthUser user) {
        var rows=jdbc.queryForList(SUMMARY+" WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=PagedRows.external(rows.getFirst());
        result.putAll(PagedRows.external(jdbc.queryForMap("SELECT notes,paid_at_sale,payment_method_name,created_at,version FROM sales WHERE id=?",id)));
        String costs=user.permissions().contains("COST_VIEW") ? ",cost_at_sale,cost_total" : "";
        result.put("items",jdbc.queryForList("SELECT product_id,container_type_id,item_name,product_code,item_type,selling_mode,quantity,unit_price,requested_amount,line_total"+costs+" FROM sale_items WHERE sale_id=? ORDER BY line_number",id).stream().map(PagedRows::external).toList());
        result.put("payments",jdbc.queryForList("SELECT cp.id,cp.payment_date,cp.payment_method_name,a.amount FROM customer_payments cp JOIN customer_payment_allocations a ON a.payment_id=cp.id WHERE a.sale_id=? ORDER BY cp.created_at,cp.id",id).stream().map(PagedRows::external).toList());
        return result;
    }
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page balances(int page) {
        PagedRows.validPage(page);
        return PagedRows.page(jdbc.queryForList("""
            SELECT c.id,c.name,sum(s.total)::numeric(18,2) sold,sum(s.paid)::numeric(18,2) paid,
            sum(s.total-s.paid)::numeric(18,2) outstanding FROM customers c JOIN sales s ON s.customer_id=c.id
            WHERE NOT c.is_walk_in GROUP BY c.id,c.name ORDER BY lower(c.name),c.id LIMIT 51 OFFSET ?
            """,(long)page*50));
    }
}
