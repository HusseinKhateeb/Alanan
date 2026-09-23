package com.alanan.accounting.common.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCancellationService {
    private final JdbcTemplate jdbc;
    public PaymentCancellationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_PAYMENTS_CREATE') and hasAuthority('AUDIT_VIEW')")
    public Map<String,Object> customer(UUID id,CancelRecordRequest request,AuthUser user) {
        var references=jdbc.queryForList("SELECT customer_id FROM customer_payments WHERE id=?",id);
        if(references.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        UUID accountId=(UUID)references.getFirst().get("customer_id");
        jdbc.queryForList("SELECT id FROM customers WHERE id=? FOR UPDATE",accountId);
        var payment=jdbc.queryForMap("SELECT id,amount FROM customer_payments WHERE id=? FOR UPDATE",id);
        if(cancelled("customer_payment_cancellations","payment_id",id)) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        if(!request.refundConfirmed()) throw new ApiException(HttpStatus.BAD_REQUEST,"REFUND_CONFIRMATION_REQUIRED");
        var allocations=jdbc.queryForList("SELECT sale_id,amount FROM customer_payment_allocations WHERE payment_id=? AND active ORDER BY sale_id",id);
        for(var allocation:allocations) {
            UUID invoiceId=(UUID)allocation.get("sale_id");
            var sale=jdbc.queryForMap("SELECT paid,paid_at_sale,total,status FROM sales WHERE id=? FOR UPDATE",invoiceId);
            if("CANCELLED".equals(sale.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"PAYMENT_INVOICE_CANCELLED");
            BigDecimal remaining=((BigDecimal)sale.get("paid")).subtract((BigDecimal)allocation.get("amount"));
            if(remaining.compareTo((BigDecimal)sale.get("paid_at_sale"))<0) throw new ApiException(HttpStatus.CONFLICT,"PAYMENT_REVERSAL_CONFLICT");
            String status=remaining.signum()==0 ? "OPEN" : remaining.compareTo((BigDecimal)sale.get("total"))==0 ? "PAID" : "PARTIAL";
            jdbc.update("UPDATE sales SET paid=?,status=?,version=version+1 WHERE id=?",remaining,status,invoiceId);
            jdbc.update("INSERT INTO sale_audit(sale_id,user_id,action,new_values) VALUES (?,?,'CUSTOMER_PAYMENT_CANCEL',jsonb_build_object('paymentId',?::text,'amount',?::numeric))",
                invoiceId,user.id(),id.toString(),allocation.get("amount"));
        }
        jdbc.update("UPDATE customer_payment_allocations SET active=FALSE WHERE payment_id=?",id);
        jdbc.update("INSERT INTO customer_payment_cancellations(payment_id,reason,refund_confirmed,cancelled_by) VALUES (?,?,TRUE,?)",id,request.reason().strip(),user.id());
        jdbc.update("INSERT INTO customer_payment_audit(payment_id,user_id,action,new_values) VALUES (?,?,'CANCEL',jsonb_build_object('reason',?,'refundedAmount',?::numeric))",
            id,user.id(),request.reason().strip(),payment.get("amount"));
        return Map.of("id",id.toString(),"status","CANCELLED");
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPPLIER_PAYMENTS_CREATE') and hasAuthority('AUDIT_VIEW')")
    public Map<String,Object> supplier(UUID id,CancelRecordRequest request,AuthUser user) {
        var references=jdbc.queryForList("SELECT supplier_id FROM supplier_payments WHERE id=?",id);
        if(references.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        UUID accountId=(UUID)references.getFirst().get("supplier_id");
        jdbc.queryForList("SELECT id FROM suppliers WHERE id=? FOR UPDATE",accountId);
        var payment=jdbc.queryForMap("SELECT id,amount FROM supplier_payments WHERE id=? FOR UPDATE",id);
        if(cancelled("supplier_payment_cancellations","payment_id",id)) throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        if(!request.refundConfirmed()) throw new ApiException(HttpStatus.BAD_REQUEST,"REFUND_RECEIPT_REQUIRED");
        var allocations=jdbc.queryForList("SELECT purchase_invoice_id,amount FROM supplier_payment_allocations WHERE payment_id=? AND active ORDER BY purchase_invoice_id",id);
        for(var allocation:allocations) {
            UUID invoiceId=(UUID)allocation.get("purchase_invoice_id");
            var invoice=jdbc.queryForMap("SELECT total,status FROM purchase_invoices WHERE id=? FOR UPDATE",invoiceId);
            if("CANCELLED".equals(invoice.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"PAYMENT_INVOICE_CANCELLED");
            BigDecimal paid=jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM supplier_payment_allocations WHERE purchase_invoice_id=? AND active",BigDecimal.class,invoiceId);
            BigDecimal remaining=paid.subtract((BigDecimal)allocation.get("amount"));
            String status=remaining.signum()==0 ? "OPEN" : remaining.compareTo((BigDecimal)invoice.get("total"))==0 ? "PAID" : "PARTIAL";
            jdbc.update("UPDATE purchase_invoices SET status=?,version=version+1 WHERE id=?",status,invoiceId);
            jdbc.update("INSERT INTO purchase_audit(purchase_invoice_id,user_id,action,new_values) VALUES (?,?,'SUPPLIER_PAYMENT_CANCEL',jsonb_build_object('paymentId',?::text,'amount',?::numeric))",
                invoiceId,user.id(),id.toString(),allocation.get("amount"));
        }
        jdbc.update("UPDATE supplier_payment_allocations SET active=FALSE WHERE payment_id=?",id);
        jdbc.update("INSERT INTO supplier_payment_cancellations(payment_id,reason,refund_confirmed,cancelled_by) VALUES (?,?,TRUE,?)",id,request.reason().strip(),user.id());
        jdbc.update("INSERT INTO supplier_payment_audit(payment_id,user_id,action,new_values) VALUES (?,?,'CANCEL',jsonb_build_object('reason',?,'refundedAmount',?::numeric))",
            id,user.id(),request.reason().strip(),payment.get("amount"));
        return Map.of("id",id.toString(),"status","CANCELLED");
    }
    private boolean cancelled(String table,String key,UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE "+key+"=?)",Boolean.class,id));
    }
}
