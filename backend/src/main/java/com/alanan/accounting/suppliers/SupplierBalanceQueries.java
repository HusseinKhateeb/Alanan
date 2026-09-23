package com.alanan.accounting.suppliers;

import com.alanan.accounting.common.api.PagedRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class SupplierBalanceQueries {
    private final JdbcTemplate jdbc;
    public SupplierBalanceQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PreAuthorize("hasAuthority('PURCHASES_VIEW')")
    public Object list(int page) {
        PagedRows.validPage(page);
        return PagedRows.page(jdbc.queryForList("""
            SELECT s.id,s.name,s.active,COALESCE(p.purchased,0)::numeric(18,2) purchased,
            COALESCE(a.paid,0)::numeric(18,2) paid,(COALESCE(p.purchased,0)-COALESCE(a.paid,0))::numeric(18,2) outstanding
            FROM suppliers s
            LEFT JOIN (SELECT supplier_id,sum(total) purchased FROM purchase_invoices WHERE status <> 'CANCELLED' GROUP BY supplier_id) p ON p.supplier_id=s.id
            LEFT JOIN (SELECT a.supplier_id,sum(a.amount) paid FROM supplier_payment_allocations a JOIN purchase_invoices i ON i.id=a.purchase_invoice_id WHERE i.status <> 'CANCELLED' GROUP BY a.supplier_id) a ON a.supplier_id=s.id
            ORDER BY lower(s.name),s.id LIMIT 51 OFFSET ?
            """,(long)page*50));
    }
}
