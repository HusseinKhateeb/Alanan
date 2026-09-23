package com.alanan.accounting.waste;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.*;
import com.alanan.accounting.common.exception.ApiException;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @PreAuthorize("hasAuthority('INVENTORY_ADJUST')")
public class WasteService {
    private final JdbcTemplate jdbc;
    private final RequestFingerprint fingerprint;
    public WasteService(JdbcTemplate jdbc,RequestFingerprint fingerprint) { this.jdbc=jdbc;this.fingerprint=fingerprint; }
    @Transactional
    public Map<String,Object> create(WasteRequest request,AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","waste:"+user.id()+":"+request.requestId());
        String hash=fingerprint.hash(request);
        var previous=jdbc.queryForList("SELECT id,request_hash FROM waste_records WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!previous.isEmpty()) {
            if(!hash.equals(previous.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return detail((UUID)previous.getFirst().get("id"),user);
        }
        var products=jdbc.queryForList("SELECT name,code,tracking_type,current_stock,current_wac FROM products WHERE id=? AND active FOR UPDATE",request.productId());
        if(products.isEmpty()) fail("PRODUCT_UNAVAILABLE"); var product=products.getFirst();
        if("PIECE".equals(product.get("tracking_type")) && request.quantity().stripTrailingZeros().scale()>0) fail("WHOLE_PIECES_REQUIRED");
        BigDecimal before=(BigDecimal)product.get("current_stock"), after=before.subtract(request.quantity());
        BigDecimal cost=(BigDecimal)product.get("current_wac"), total=cost==null ? null : cost.multiply(request.quantity()).setScale(2,RoundingMode.HALF_UP);
        if(after.precision()>18 || (total!=null && total.precision()>18)) fail("WASTE_AMOUNT_INVALID");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO waste_records(id,product_id,product_name,product_code,tracking_type,quantity,waste_date,reason,notes,cost_at_waste,cost_total,negative_stock,created_by,request_id,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,request.productId(),product.get("name"),product.get("code"),product.get("tracking_type"),request.quantity(),request.wasteDate(),request.reason(),request.notes(),cost,total,after.signum()<0,user.id(),request.requestId(),hash);
        jdbc.update("UPDATE products SET current_stock=?,version=version+1 WHERE id=?",after,request.productId());
        jdbc.update("INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,waste_id,unit_cost,notes,created_by) VALUES (?,?,'WASTE',?,'WASTE',?,?,?,?,?)",
            UUID.randomUUID(),request.productId(),request.quantity().negate(),id,id,cost,request.reason(),user.id());
        jdbc.update("INSERT INTO waste_audit(waste_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",id,user.id(),fingerprint.serialize(Map.of("request",request,"oldStock",before,"newStock",after,"negativeStock",after.signum()<0)));
        return detail(id,user);
    }
    private String projection(AuthUser user) {
        return "SELECT id,product_id,product_name,product_code,tracking_type,quantity,waste_date,reason,notes,negative_stock,created_at"+
            (user.permissions().contains("COST_VIEW") ? ",cost_at_waste,cost_total" : "")+" FROM waste_records";
    }
    public Map<String,Object> detail(UUID id,AuthUser user) {
        var rows=jdbc.queryForList(projection(user)+" WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        return PagedRows.external(rows.getFirst());
    }
    public PagedRows.Page list(int page,LocalDate from,LocalDate to,UUID productId,AuthUser user) {
        PagedRows.validPage(page);
        if(from!=null && to!=null && from.isAfter(to)) fail("INVALID_DATE_RANGE");
        var sql=new StringBuilder(projection(user)+" WHERE 1=1"); var args=new ArrayList<Object>();
        if(from!=null) { sql.append(" AND waste_date>=?");args.add(from); }
        if(to!=null) { sql.append(" AND waste_date<=?");args.add(to); }
        if(productId!=null) { sql.append(" AND product_id=?");args.add(productId); }
        sql.append(" ORDER BY waste_date DESC,created_at DESC,id LIMIT 51 OFFSET ?");args.add((long)page*50);
        return PagedRows.page(jdbc.queryForList(sql.toString(),args.toArray()));
    }
    private void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST,code); }
}
