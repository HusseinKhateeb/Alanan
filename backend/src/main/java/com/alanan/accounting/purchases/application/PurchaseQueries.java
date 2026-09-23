package com.alanan.accounting.purchases.application;

import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.api.PagedRows.Page;
import static com.alanan.accounting.common.api.PagedRows.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseQueries {
    private final JdbcTemplate jdbc;
    public PurchaseQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SUMMARY="""
        SELECT p.id,p.invoice_number,p.supplier_id,p.supplier_name,p.invoice_date,p.total,p.status,
        COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id AND a.active),0)::numeric(18,2) paid,
        (CASE WHEN p.status='CANCELLED' THEN 0 ELSE p.total-COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id AND a.active),0) END)::numeric(18,2) outstanding
        FROM purchase_invoices p
        """;
    @PreAuthorize("hasAuthority('PURCHASES_VIEW')")
    public Page list(int page, UUID supplierId) {
        validPage(page);
        var rows=supplierId==null ? jdbc.queryForList(SUMMARY + " ORDER BY p.invoice_date DESC,p.created_at DESC,p.id LIMIT 51 OFFSET ?",(long)page*50)
            : jdbc.queryForList(SUMMARY + " WHERE p.supplier_id=? ORDER BY p.invoice_date DESC,p.created_at DESC,p.id LIMIT 51 OFFSET ?",supplierId,(long)page*50);
        return page(rows);
    }
    @Transactional(readOnly=true)
    @PreAuthorize("hasAuthority('PURCHASES_VIEW')")
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList(SUMMARY + " WHERE p.id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=external(rows.getFirst());
        result.putAll(external(jdbc.queryForMap("SELECT supplier_reference,notes,created_by,created_at,version FROM purchase_invoices WHERE id=?",id)));
        result.put("items",jdbc.queryForList("SELECT product_id,product_name,product_code,tracking_type,quantity,unit_cost,line_total FROM purchase_items WHERE purchase_invoice_id=? ORDER BY line_number",id).stream().map(PagedRows::external).toList());
        result.put("payments",jdbc.queryForList("SELECT p.id,p.payment_date,p.payment_method_name,a.amount,a.active FROM supplier_payments p JOIN supplier_payment_allocations a ON a.payment_id=p.id WHERE a.purchase_invoice_id=? ORDER BY p.created_at,p.id",id).stream().map(PagedRows::external).toList());
        var cancellations=jdbc.queryForList("SELECT reason,refund_received,refund_method_name,created_at FROM purchase_cancellations WHERE purchase_invoice_id=?",id);
        result.put("cancellation",cancellations.isEmpty() ? null : PagedRows.external(cancellations.getFirst()));
        return result;
    }
}
