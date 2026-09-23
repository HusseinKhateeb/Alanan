package com.alanan.accounting.dashboard;

import com.alanan.accounting.auth.AuthUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardQueries {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hebron");
    private final JdbcTemplate jdbc;
    public DashboardQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Map<String,Object> overview(AuthUser user) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate month = today.withDayOfMonth(1);
        LocalDate nextMonth = month.plusMonths(1);
        var result = new LinkedHashMap<String,Object>();
        result.put("asOf", today);
        if (user.permissions().contains("SALES_VIEW")) {
            result.put("salesToday", amount("SELECT COALESCE(sum(total),0) FROM sales WHERE status <> 'CANCELLED' AND invoice_date=?", today));
            result.put("salesMonth", amount("SELECT COALESCE(sum(total),0) FROM sales WHERE status <> 'CANCELLED' AND invoice_date>=? AND invoice_date<?", month,nextMonth));
            result.put("containerRevenueMonth", amount("""
                SELECT COALESCE(sum(i.line_total),0) FROM sale_items i JOIN sales s ON s.id=i.sale_id
                WHERE s.status <> 'CANCELLED' AND i.item_type='SPICE_CONTAINER' AND s.invoice_date>=? AND s.invoice_date<?
                """,month,nextMonth));
            var daily = new HashMap<LocalDate,BigDecimal>();
            for (var row : jdbc.queryForList("""
                SELECT invoice_date AS sales_day, sum(total)::numeric(18,2) AS total FROM sales
                WHERE status <> 'CANCELLED' AND invoice_date>=? AND invoice_date<?
                GROUP BY invoice_date ORDER BY invoice_date
                """,today.minusDays(6),today.plusDays(1))) {
                daily.put(LocalDate.parse(row.get("sales_day").toString()),(BigDecimal)row.get("total"));
            }
            var trend = new ArrayList<Map<String,String>>();
            for (int daysAgo=6; daysAgo>=0; daysAgo--) {
                LocalDate day=today.minusDays(daysAgo);
                trend.add(Map.of("day",day.toString(),"total",daily.getOrDefault(day,BigDecimal.ZERO).setScale(2).toPlainString()));
            }
            result.put("salesTrend",trend);
        }
        if (user.permissions().contains("PURCHASES_VIEW")) {
            result.put("purchasesMonth",amount("SELECT COALESCE(sum(total),0) FROM purchase_invoices WHERE status <> 'CANCELLED' AND invoice_date>=? AND invoice_date<?",month,nextMonth));
            result.put("supplierDebt",amount("""
                SELECT COALESCE(sum(p.total-COALESCE(a.paid,0)),0) FROM purchase_invoices p
                LEFT JOIN (SELECT purchase_invoice_id,sum(amount) paid FROM supplier_payment_allocations WHERE active GROUP BY purchase_invoice_id) a
                ON a.purchase_invoice_id=p.id WHERE p.status <> 'CANCELLED'
                """));
        }
        if (user.permissions().contains("EXPENSES_CREATE")) {
            result.put("expensesMonth",amount("SELECT COALESCE(sum(e.amount),0) FROM expenses e WHERE NOT EXISTS(SELECT 1 FROM expense_cancellations c WHERE c.expense_id=e.id) AND e.expense_date>=? AND e.expense_date<?",month,nextMonth));
        }
        if (user.permissions().contains("COST_VIEW") && user.permissions().contains("SALES_VIEW")) {
            BigDecimal knownRevenue = decimal("""
                SELECT COALESCE(sum(i.line_total),0) FROM sale_items i JOIN sales s ON s.id=i.sale_id
                WHERE s.status <> 'CANCELLED' AND i.item_type<>'SPICE_CONTAINER' AND i.cost_total IS NOT NULL
                AND s.invoice_date>=? AND s.invoice_date<?
                """,month,nextMonth);
            BigDecimal knownCost = decimal("""
                SELECT COALESCE(sum(i.cost_total),0) FROM sale_items i JOIN sales s ON s.id=i.sale_id
                WHERE s.status <> 'CANCELLED' AND i.item_type<>'SPICE_CONTAINER' AND i.cost_total IS NOT NULL
                AND s.invoice_date>=? AND s.invoice_date<?
                """,month,nextMonth);
            BigDecimal gross = knownRevenue.subtract(knownCost);
            result.put("knownCogsMonth",knownCost.toPlainString());
            result.put("knownGrossProfitMonth",gross.toPlainString());
            if (user.permissions().contains("EXPENSES_CREATE")) {
                result.put("estimatedNetProfitMonth",gross.subtract(decimal(
                    "SELECT COALESCE(sum(e.amount),0) FROM expenses e WHERE NOT EXISTS(SELECT 1 FROM expense_cancellations c WHERE c.expense_id=e.id) AND e.expense_date>=? AND e.expense_date<?",month,nextMonth)).toPlainString());
            }
        }
        return result;
    }
    private BigDecimal decimal(String sql,Object... args) { return jdbc.queryForObject(sql,BigDecimal.class,args).setScale(2); }
    private String amount(String sql,Object... args) { return decimal(sql,args).toPlainString(); }
}
