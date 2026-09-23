package com.alanan.accounting.customerpayments.application;

import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class CustomerPaymentQueries {
    private final JdbcTemplate jdbc;
    public CustomerPaymentQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SUMMARY="SELECT id,customer_id,customer_name,amount,payment_date,payment_method_name,allocation_mode FROM customer_payments";
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PagedRows.Page list(int page,UUID customerId) {
        PagedRows.validPage(page);
        return PagedRows.page(customerId==null
            ? jdbc.queryForList(SUMMARY+" ORDER BY payment_date DESC,created_at DESC,id LIMIT 51 OFFSET ?",(long)page*50)
            : jdbc.queryForList(SUMMARY+" WHERE customer_id=? ORDER BY payment_date DESC,created_at DESC,id LIMIT 51 OFFSET ?",customerId,(long)page*50));
    }
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList("SELECT id,customer_id,customer_name,amount,payment_date,payment_method_name,allocation_mode,notes,created_by,created_at FROM customer_payments WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=PagedRows.external(rows.getFirst());
        result.put("allocations",jdbc.queryForList("SELECT a.sale_id invoice_id,p.invoice_number,a.amount FROM customer_payment_allocations a JOIN sales p ON p.id=a.sale_id WHERE a.payment_id=? ORDER BY p.invoice_date,p.created_at,p.id",id).stream().map(PagedRows::external).toList());
        return result;
    }
    @PreAuthorize("hasAnyAuthority('SALES_VIEW','CUSTOMER_PAYMENTS_CREATE')")
    public PagedRows.Page openInvoices(UUID customerId,int page) {
        PagedRows.validPage(page);
        return PagedRows.page(jdbc.queryForList("""
            SELECT p.id,p.invoice_number,p.invoice_date,p.total,p.status,
            (p.total-p.paid)::numeric(18,2) outstanding
            FROM sales p WHERE p.customer_id=? AND p.status<>'CANCELLED'
            AND p.total>p.paid
            ORDER BY p.invoice_date,p.created_at,p.id LIMIT 51 OFFSET ?
            """,customerId,(long)page*50));
    }
}
