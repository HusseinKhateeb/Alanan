package com.alanan.accounting.audit;

import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

@Service
public class AuditQueries {
    private final JdbcTemplate jdbc;
    public AuditQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String EVENTS="""
        SELECT id,entity_type,entity_id,action,user_id,created_at,old_values::text old_values,new_values::text new_values FROM master_data_audit
        UNION ALL SELECT id,'PURCHASE',purchase_invoice_id::text,action,user_id,created_at,NULL,new_values::text FROM purchase_audit
        UNION ALL SELECT id,'SALE',sale_id::text,action,user_id,created_at,NULL,new_values::text FROM sale_audit
        UNION ALL SELECT id,'SUPPLIER_PAYMENT',payment_id::text,action,user_id,created_at,NULL,new_values::text FROM supplier_payment_audit
        UNION ALL SELECT id,'CUSTOMER_PAYMENT',payment_id::text,action,user_id,created_at,NULL,new_values::text FROM customer_payment_audit
        UNION ALL SELECT id,'EXPENSE',expense_id::text,action,user_id,created_at,NULL,new_values::text FROM expense_audit
        UNION ALL SELECT id,'WASTE',waste_id::text,action,user_id,created_at,NULL,new_values::text FROM waste_audit
        UNION ALL SELECT id,'AUTH',COALESCE(user_id::text,''),action,user_id,created_at,NULL,NULL FROM auth_events
        """;
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public PagedRows.Page list(int page,String type,UUID actor,LocalDate from,LocalDate to) {
        PagedRows.validPage(page);
        if (from != null && to != null && from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED");
        var sql=new StringBuilder("SELECT e.id,e.entity_type,e.entity_id,e.action,e.user_id,u.display_name actor_name,e.created_at,e.old_values,e.new_values FROM ("+EVENTS+") e LEFT JOIN users u ON u.id=e.user_id WHERE 1=1");
        var args=new ArrayList<Object>();
        if(type!=null && !type.isBlank()) { sql.append(" AND e.entity_type=?"); args.add(type.toUpperCase(Locale.ROOT)); }
        if(actor!=null) { sql.append(" AND e.user_id=?"); args.add(actor); }
        if(from!=null) { sql.append(" AND e.created_at>=?"); args.add(from.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime()); }
        if(to!=null) { sql.append(" AND e.created_at<?"); args.add(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime()); }
        sql.append(" ORDER BY e.created_at DESC,e.entity_type,e.id DESC LIMIT 51 OFFSET ?"); args.add((long)page*50);
        return PagedRows.page(jdbc.queryForList(sql.toString(),args.toArray()));
    }
}
