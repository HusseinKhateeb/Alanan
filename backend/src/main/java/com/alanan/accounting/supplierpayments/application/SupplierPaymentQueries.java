package com.alanan.accounting.supplierpayments.application;

import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class SupplierPaymentQueries {
    private final JdbcTemplate jdbc;
    public SupplierPaymentQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SUMMARY="SELECT p.id,p.supplier_id,p.supplier_name,p.amount,p.payment_date,p.payment_method_name,p.allocation_mode,(c.payment_id IS NOT NULL) cancelled FROM supplier_payments p LEFT JOIN supplier_payment_cancellations c ON c.payment_id=p.id";
    @PreAuthorize("hasAuthority('PURCHASES_VIEW')")
    public PagedRows.Page list(int page,UUID supplierId) {
        PagedRows.validPage(page);
        return PagedRows.page(supplierId==null
            ? jdbc.queryForList(SUMMARY+" ORDER BY p.payment_date DESC,p.created_at DESC,p.id LIMIT 51 OFFSET ?",(long)page*50)
            : jdbc.queryForList(SUMMARY+" WHERE p.supplier_id=? ORDER BY p.payment_date DESC,p.created_at DESC,p.id LIMIT 51 OFFSET ?",supplierId,(long)page*50));
    }
    @PreAuthorize("hasAuthority('PURCHASES_VIEW')")
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList("SELECT id,supplier_id,supplier_name,amount,payment_date,payment_method_name,allocation_mode,notes,created_by,created_at FROM supplier_payments WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=PagedRows.external(rows.getFirst());
        result.put("allocations",jdbc.queryForList("SELECT a.purchase_invoice_id invoice_id,p.invoice_number,a.amount FROM supplier_payment_allocations a JOIN purchase_invoices p ON p.id=a.purchase_invoice_id WHERE a.payment_id=? ORDER BY p.invoice_date,p.created_at,p.id",id).stream().map(PagedRows::external).toList());
        var cancellations=jdbc.queryForList("SELECT reason,created_at FROM supplier_payment_cancellations WHERE payment_id=?",id);
        result.put("cancellation",cancellations.isEmpty()?null:PagedRows.external(cancellations.getFirst()));
        return result;
    }
    @PreAuthorize("hasAnyAuthority('PURCHASES_VIEW','SUPPLIER_PAYMENTS_CREATE')")
    public PagedRows.Page openInvoices(UUID supplierId,int page) {
        PagedRows.validPage(page);
        return PagedRows.page(jdbc.queryForList("""
            SELECT p.id,p.invoice_number,p.invoice_date,p.total,p.status,
            (p.total-COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id AND a.active),0))::numeric(18,2) outstanding
            FROM purchase_invoices p WHERE p.supplier_id=? AND p.status<>'CANCELLED'
            AND p.total>COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id AND a.active),0)
            ORDER BY p.invoice_date,p.created_at,p.id LIMIT 51 OFFSET ?
            """,supplierId,(long)page*50));
    }
}
