package com.alanan.accounting.inventory;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("hasAnyAuthority('PURCHASES_VIEW','PURCHASES_CREATE','INVENTORY_ADJUST','COST_VIEW')")
public class InventoryQueries {
    private final JdbcTemplate jdbc;
    public InventoryQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Object stock(int page,AuthUser user) {
        PagedRows.validPage(page);
        String costs=user.permissions().contains("COST_VIEW") ? ",current_wac" : "";
        return PagedRows.page(jdbc.queryForList("SELECT id,name,code,tracking_type,current_stock,active"+costs+" FROM products ORDER BY lower(name),id LIMIT 51 OFFSET ?",(long)page*50));
    }
    public Object movements(UUID productId,int page,AuthUser user) {
        PagedRows.validPage(page);
        String costs=user.permissions().contains("COST_VIEW") ? ",m.unit_cost" : "";
        return PagedRows.page(jdbc.queryForList("SELECT m.id,m.movement_type,m.quantity_change,m.created_at,m.reference_id,p.invoice_number"+costs+
            " FROM inventory_movements m LEFT JOIN purchase_invoices p ON p.id=m.reference_id AND m.reference_type='PURCHASE' WHERE m.product_id=? ORDER BY m.created_at DESC,m.id LIMIT 51 OFFSET ?",productId,(long)page*50));
    }
}
