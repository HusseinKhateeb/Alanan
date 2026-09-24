package com.alanan.accounting.inventory;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.RequestFingerprint;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.purchases.domain.PurchaseMath;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpeningStockService {
    private final JdbcTemplate jdbc;
    private final RequestFingerprint fingerprint;
    public OpeningStockService(JdbcTemplate jdbc, RequestFingerprint fingerprint) { this.jdbc=jdbc; this.fingerprint=fingerprint; }

    @Transactional
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')")
    public Map<String,Object> add(OpeningStockRequest request, AuthUser user) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "opening-stock:"+user.id()+":"+request.requestId());
        String hash=fingerprint.hash(request);
        var prior=jdbc.queryForList("SELECT id,request_hash FROM inventory_adjustments WHERE created_by=? AND request_id=?",user.id(),request.requestId());
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return receipt((UUID)prior.getFirst().get("id"));
        }
        var rows=jdbc.queryForList("SELECT name,code,tracking_type,current_stock,current_wac FROM products WHERE id=? AND active FOR UPDATE",request.productId());
        if(rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"PRODUCT_UNAVAILABLE");
        var product=rows.getFirst();
        if("PIECE".equals(product.get("tracking_type")) && request.quantity().stripTrailingZeros().scale()>0)
            PurchaseMath.fail("WHOLE_PIECES_REQUIRED");
        BigDecimal before=(BigDecimal)product.get("current_stock"), oldWac=(BigDecimal)product.get("current_wac");
        BigDecimal after=before.add(request.quantity());
        if(after.precision()>18) PurchaseMath.fail("PURCHASE_AMOUNT_INVALID");
        BigDecimal newWac=PurchaseMath.wac(before,oldWac,request.quantity(),request.quantity().multiply(request.unitCost()));
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory_adjustments(id,product_id,product_name,product_code,tracking_type,quantity,unit_cost,recorded_date,reason,
                old_stock,new_stock,old_wac,new_wac,created_by,request_id,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,request.productId(),product.get("name"),product.get("code"),product.get("tracking_type"),request.quantity(),request.unitCost(),
            request.recordedDate(),request.reason().strip(),before,after,oldWac,newWac,user.id(),request.requestId(),hash);
        jdbc.update("UPDATE products SET current_stock=?,current_wac=?,version=version+1 WHERE id=?",after,newWac,request.productId());
        jdbc.update("""
            INSERT INTO inventory_movements(id,product_id,movement_type,quantity_change,reference_type,reference_id,unit_cost,notes,created_by)
            VALUES (?,?,'OPENING',?,'OPENING_STOCK',?,?,?,?)
            """,UUID.randomUUID(),request.productId(),request.quantity(),id,request.unitCost(),request.reason().strip(),user.id());
        var audit=new LinkedHashMap<String,Object>();
        audit.put("request",request); audit.put("oldStock",before); audit.put("newStock",after);
        audit.put("oldWac",oldWac); audit.put("newWac",newWac);
        jdbc.update("INSERT INTO inventory_adjustment_audit(adjustment_id,user_id,action,new_values) VALUES (?,?,'CREATE',?::jsonb)",
            id,user.id(),fingerprint.serialize(audit));
        return receipt(id);
    }

    private Map<String,Object> receipt(UUID id) {
        var row=jdbc.queryForMap("SELECT id,product_id,quantity,old_stock,new_stock,recorded_date,reason FROM inventory_adjustments WHERE id=?",id);
        return com.alanan.accounting.common.api.PagedRows.external(row);
    }
}
