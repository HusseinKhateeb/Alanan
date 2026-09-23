package com.alanan.accounting.reports;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportQueries {
    private final JdbcTemplate jdbc;
    public ReportQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Report(LocalDate from,LocalDate to,Map<String,Object> totals,PagedRows.Page rows) {}

    @Transactional(readOnly=true)
    @PreAuthorize("isAuthenticated()")
    public Report report(String kind,LocalDate from,LocalDate to,int page,AuthUser user) {
        PagedRows.validPage(page);
        if(from==null || to==null || from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE");
        return switch(kind) {
            case "sales" -> { require(user,"SALES_VIEW"); yield sales(from,to,page); }
            case "purchases" -> { require(user,"PURCHASES_VIEW"); yield purchases(from,to,page); }
            case "expenses" -> { require(user,"EXPENSES_CREATE"); yield expenses(from,to,page); }
            case "suppliers" -> { require(user,"PURCHASES_VIEW"); yield suppliers(from,to,page); }
            case "profit" -> {
                require(user,"SALES_VIEW"); require(user,"COST_VIEW"); require(user,"EXPENSES_CREATE");
                yield profit(from,to,page);
            }
            default -> throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        };
    }
    private static void require(AuthUser user,String permission) {
        if(!user.permissions().contains(permission)) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN");
    }
    private Report sales(LocalDate from,LocalDate to,int page) {
        String where=" FROM sales s WHERE s.status<>'CANCELLED' AND s.invoice_date>=? AND s.invoice_date<=?";
        var totals=totals("SELECT count(*) invoice_count,COALESCE(sum(s.total),0)::numeric(18,2) total,COALESCE(sum(s.paid),0)::numeric(18,2) paid,COALESCE(sum(s.total-s.paid),0)::numeric(18,2) outstanding"+where,from,to);
        var rows=jdbc.queryForList("SELECT s.id,s.invoice_number,s.invoice_date,s.customer_name,s.status,s.total,s.paid,(s.total-s.paid)::numeric(18,2) outstanding"+where+
            " ORDER BY s.invoice_date DESC,s.created_at DESC,s.id LIMIT 51 OFFSET ?",from,to,(long)page*50);
        return new Report(from,to,totals,PagedRows.page(rows));
    }
    private Report purchases(LocalDate from,LocalDate to,int page) {
        String paid="COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a WHERE a.purchase_invoice_id=p.id AND a.active),0)";
        String where=" FROM purchase_invoices p WHERE p.status<>'CANCELLED' AND p.invoice_date>=? AND p.invoice_date<=?";
        var totals=totals("SELECT count(*) invoice_count,COALESCE(sum(p.total),0)::numeric(18,2) total,COALESCE(sum("+paid+"),0)::numeric(18,2) paid,COALESCE(sum(p.total-"+paid+"),0)::numeric(18,2) outstanding"+where,from,to);
        var rows=jdbc.queryForList("SELECT p.id,p.invoice_number,p.invoice_date,p.supplier_name,p.status,p.total,"+paid+"::numeric(18,2) paid,(p.total-"+paid+")::numeric(18,2) outstanding"+where+
            " ORDER BY p.invoice_date DESC,p.created_at DESC,p.id LIMIT 51 OFFSET ?",from,to,(long)page*50);
        return new Report(from,to,totals,PagedRows.page(rows));
    }
    private Report expenses(LocalDate from,LocalDate to,int page) {
        String where=" FROM expenses e WHERE NOT EXISTS(SELECT 1 FROM expense_cancellations c WHERE c.expense_id=e.id) AND e.expense_date>=? AND e.expense_date<=?";
        var totals=totals("SELECT count(*) expense_count,COALESCE(sum(e.amount),0)::numeric(18,2) total"+where,from,to);
        var rows=jdbc.queryForList("SELECT e.id,e.expense_date,e.category_name,e.payment_method_name,e.amount,e.notes"+where+
            " ORDER BY e.expense_date DESC,e.created_at DESC,e.id LIMIT 51 OFFSET ?",from,to,(long)page*50);
        return new Report(from,to,totals,PagedRows.page(rows));
    }
    private Report suppliers(LocalDate from,LocalDate to,int page) {
        String query="""
            WITH purchased AS (
              SELECT supplier_id,sum(total) amount FROM purchase_invoices
              WHERE status<>'CANCELLED' AND invoice_date>=? AND invoice_date<=? GROUP BY supplier_id
            ), paid AS (
              SELECT p.supplier_id,sum(a.amount) amount FROM supplier_payment_allocations a
              JOIN supplier_payments p ON p.id=a.payment_id
              JOIN purchase_invoices i ON i.id=a.purchase_invoice_id
              WHERE a.active AND i.status<>'CANCELLED' AND p.payment_date>=? AND p.payment_date<=?
              GROUP BY p.supplier_id
            ), debt AS (
              SELECT i.supplier_id,sum(i.total-COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocations a
                WHERE a.purchase_invoice_id=i.id AND a.active),0)) amount
              FROM purchase_invoices i WHERE i.status<>'CANCELLED' GROUP BY i.supplier_id
            )
            SELECT s.id,s.name,COALESCE(purchased.amount,0)::numeric(18,2) purchased,
              COALESCE(paid.amount,0)::numeric(18,2) paid,COALESCE(debt.amount,0)::numeric(18,2) current_outstanding
            FROM suppliers s LEFT JOIN purchased ON purchased.supplier_id=s.id
            LEFT JOIN paid ON paid.supplier_id=s.id LEFT JOIN debt ON debt.supplier_id=s.id
            """;
        Object[] dates={from,to,from,to};
        var totals=totals("SELECT count(*) supplier_count,COALESCE(sum(purchased),0)::numeric(18,2) purchased,COALESCE(sum(paid),0)::numeric(18,2) paid,COALESCE(sum(current_outstanding),0)::numeric(18,2) current_outstanding FROM ("+query+") r",dates);
        var rows=jdbc.queryForList(query+" ORDER BY lower(s.name),s.id LIMIT 51 OFFSET ?",from,to,from,to,(long)page*50);
        return new Report(from,to,totals,PagedRows.page(rows));
    }
    private Report profit(LocalDate from,LocalDate to,int page) {
        String base="""
            FROM sales s JOIN sale_items i ON i.sale_id=s.id
            WHERE s.status<>'CANCELLED' AND s.invoice_date>=? AND s.invoice_date<=?
            """;
        String knownRevenue="COALESCE(sum(CASE WHEN i.item_type<>'SPICE_CONTAINER' AND i.cost_total IS NOT NULL THEN i.line_total ELSE 0 END),0)::numeric(18,2)";
        String knownCost="COALESCE(sum(CASE WHEN i.item_type<>'SPICE_CONTAINER' AND i.cost_total IS NOT NULL THEN i.cost_total ELSE 0 END),0)::numeric(18,2)";
        String unknownRevenue="COALESCE(sum(CASE WHEN i.item_type='SPICE_CONTAINER' OR i.cost_total IS NULL THEN i.line_total ELSE 0 END),0)::numeric(18,2)";
        var totals=totals("SELECT "+knownRevenue+" known_revenue,"+knownCost+" known_cost,"+unknownRevenue+" unknown_cost_revenue "+base,from,to);
        BigDecimal revenue=new BigDecimal(totals.get("known_revenue").toString());
        BigDecimal cost=new BigDecimal(totals.get("known_cost").toString());
        BigDecimal expenses=jdbc.queryForObject("SELECT COALESCE(sum(e.amount),0)::numeric(18,2) FROM expenses e WHERE NOT EXISTS(SELECT 1 FROM expense_cancellations c WHERE c.expense_id=e.id) AND e.expense_date>=? AND e.expense_date<=?",BigDecimal.class,from,to);
        totals.put("known_gross_profit",revenue.subtract(cost).toPlainString());
        totals.put("expenses",expenses.toPlainString());
        totals.put("known_margin_less_expenses",revenue.subtract(cost).subtract(expenses).toPlainString());
        var rows=jdbc.queryForList("SELECT s.id,s.invoice_number,s.invoice_date,s.customer_name,"+knownRevenue+" known_revenue,"+knownCost+" known_cost,"+unknownRevenue+" unknown_cost_revenue,("+knownRevenue+"-"+knownCost+")::numeric(18,2) known_gross_profit "+base+
            " GROUP BY s.id,s.invoice_number,s.invoice_date,s.customer_name,s.created_at ORDER BY s.invoice_date DESC,s.created_at DESC,s.id LIMIT 51 OFFSET ?",from,to,(long)page*50);
        return new Report(from,to,totals,PagedRows.page(rows));
    }
    private Map<String,Object> totals(String sql,Object... args) { return PagedRows.external(jdbc.queryForMap(sql,args)); }
}
