package com.alanan.accounting.expenses;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.*;
import com.alanan.accounting.common.exception.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAuthority('EXPENSES_CREATE')")
public class ExpenseService {
    private final JdbcTemplate jdbc;
    private final RequestFingerprint fingerprint;
    public ExpenseService(JdbcTemplate jdbc,RequestFingerprint fingerprint) { this.jdbc=jdbc;this.fingerprint=fingerprint; }
    @Transactional
    public Map<String,Object> create(ExpenseRequest request,AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","expense:"+user.id()+":"+request.requestId());
        String hash=fingerprint.hash(request);
        var previous=jdbc.queryForList("SELECT id,request_hash FROM expenses WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return detail((UUID)previous.getFirst().get("id"));
        }
        var categories=jdbc.queryForList("SELECT name FROM expense_categories WHERE id=? AND active FOR SHARE",request.categoryId());
        if(categories.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"EXPENSE_CATEGORY_UNAVAILABLE");
        var methods=request.paymentMethodId()==null ? jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND is_default FOR SHARE")
            : jdbc.queryForList("SELECT id,name FROM payment_methods WHERE active AND id=? FOR SHARE",request.paymentMethodId());
        if(methods.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_METHOD_UNAVAILABLE");
        UUID id=UUID.randomUUID(); var method=methods.getFirst();
        jdbc.update("INSERT INTO expenses(id,category_id,category_name,amount,expense_date,payment_method_id,payment_method_name,notes,created_by,request_id,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id,request.categoryId(),categories.getFirst().get("name"),request.amount(),request.expenseDate(),method.get("id"),method.get("name"),request.notes(),user.id(),request.requestId(),hash);
        jdbc.update("INSERT INTO expense_audit(expense_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",id,user.id(),fingerprint.serialize(request));
        return detail(id);
    }
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList("SELECT id,category_id,category_name,amount,expense_date,payment_method_name,notes,created_at FROM expenses WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var result=PagedRows.external(rows.getFirst());
        var cancellations=jdbc.queryForList("SELECT reason,created_at FROM expense_cancellations WHERE expense_id=?",id);
        result.put("cancellation",cancellations.isEmpty()?null:PagedRows.external(cancellations.getFirst()));
        return result;
    }
    @Transactional
    public Map<String,Object> cancel(UUID id,com.alanan.accounting.common.api.CancelRecordRequest request,AuthUser user) {
        var rows=jdbc.queryForList("SELECT id,amount,expense_date FROM expenses WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM expense_cancellations WHERE expense_id=?)",Boolean.class,id)))
            throw new ApiException(HttpStatus.CONFLICT,"ALREADY_CANCELLED");
        if(!request.refundConfirmed()) throw new ApiException(HttpStatus.BAD_REQUEST,"REFUND_CONFIRMATION_REQUIRED");
        jdbc.update("INSERT INTO expense_cancellations(expense_id,reason,cancelled_by) VALUES (?,?,?)",id,request.reason().strip(),user.id());
        jdbc.update("INSERT INTO expense_audit(expense_id,user_id,action,new_values) VALUES (?,?,'CANCEL',jsonb_build_object('reason',?,'originalAmount',?::numeric))",
            id,user.id(),request.reason().strip(),rows.getFirst().get("amount"));
        return detail(id);
    }
    public PagedRows.Page list(int page,LocalDate from,LocalDate to,UUID categoryId) {
        PagedRows.validPage(page);
        if(from!=null && to!=null && from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE");
        var sql=new StringBuilder("SELECT e.id,e.category_id,e.category_name,e.amount,e.expense_date,e.payment_method_name,e.notes,c.reason cancellation_reason FROM expenses e LEFT JOIN expense_cancellations c ON c.expense_id=e.id WHERE 1=1");
        var args=new ArrayList<Object>();
        if(from!=null) { sql.append(" AND e.expense_date>=?");args.add(from); }
        if(to!=null) { sql.append(" AND e.expense_date<=?");args.add(to); }
        if(categoryId!=null) { sql.append(" AND e.category_id=?");args.add(categoryId); }
        sql.append(" ORDER BY e.expense_date DESC,e.created_at DESC,e.id LIMIT 51 OFFSET ?");args.add((long)page*50);
        return PagedRows.page(jdbc.queryForList(sql.toString(),args.toArray()));
    }
}
