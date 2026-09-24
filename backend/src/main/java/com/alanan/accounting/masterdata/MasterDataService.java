package com.alanan.accounting.masterdata;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.common.api.RequestFingerprint;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Common lifecycle for reference records; accounting mutations belong to their own services. */
@Service
public class MasterDataService {
    private final JdbcTemplate jdbc;
    private final RequestFingerprint fingerprint;
    public MasterDataService(JdbcTemplate jdbc, RequestFingerprint fingerprint) { this.jdbc = jdbc; this.fingerprint = fingerprint; }
    public record Page(List<Map<String,Object>> items, boolean hasMore) {}

    @Transactional
    @PreAuthorize("hasAuthority('SALES_CREATE')")
    public Map<String,Object> quickCreateCustomer(QuickCustomerInput input, AuthUser user) {
        String hash=fingerprint.hash(Map.of("name",input.name().strip(),"phone",Objects.toString(PhoneNormalizer.normalize(input.phone()),"")));
        if(input.requestId()!=null) {
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user.id());
            var prior=jdbc.queryForList("SELECT request_hash,customer_id FROM quick_customer_requests WHERE user_id=? AND request_id=?",user.id(),input.requestId());
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.getFirst().get("request_hash"))) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
                return external(jdbc.queryForMap("SELECT " + columns(Catalog.CUSTOMERS) + " FROM customers WHERE id=?",prior.getFirst().get("customer_id")));
            }
        }
        var customer=save(Catalog.CUSTOMERS, null,
            new MasterDataInput(input.name(), null, null, null, null, input.phone(), null, null, true, null, 0L), user);
        if(input.requestId()!=null) jdbc.update("INSERT INTO quick_customer_requests(user_id,request_id,request_hash,customer_id) VALUES (?,?,?,?)",
            user.id(),input.requestId(),hash,UUID.fromString((String)customer.get("id")));
        return customer;
    }

    @Transactional
    @PreAuthorize("hasAuthority('EXPENSES_CREATE')")
    public Map<String,Object> quickCreateExpenseCategory(QuickExpenseCategoryInput input, AuthUser user) {
        return save(Catalog.EXPENSES, null,
            new MasterDataInput(input.name(), null, null, null, null, null, null, null, true, null, 0L), user);
    }

    @Transactional
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')")
    public Map<String,Object> quickCreateWasteProduct(QuickWasteProductInput input, AuthUser user) {
        return save(Catalog.PRODUCTS, null,
            new MasterDataInput(input.name(), input.code(), input.category(), input.trackingType(), input.price(), null, null, null, true, null, 0L), user);
    }

    public Page list(Catalog catalog, String query, int page, boolean activeOnly, String phone, AuthUser user) {
        requireRead(catalog, user);
        if (page < 0 || query.length() > 200 || (phone != null && phone.length() > 60)) fail("VALIDATION_FAILED");
        var parameters = new ArrayList<Object>();
        String where = " WHERE TRUE";
        if (activeOnly) where += " AND active";
        if (phone != null && !phone.isBlank()) {
            if (catalog != Catalog.CUSTOMERS) fail("VALIDATION_FAILED");
            where += " AND normalized_phone = ?";
            parameters.add(PhoneNormalizer.normalize(phone));
        }
        if (!query.isBlank()) {
            String prefix = query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where += " AND (lower(name) LIKE ?";
            parameters.add(prefix);
            if (catalog == Catalog.PRODUCTS) { where += " OR lower(code) LIKE ?"; parameters.add(prefix); }
            if (catalog == Catalog.CUSTOMERS) {
                String digits = query.replaceAll("[^0-9٠-٩۰-۹]", "");
                if (!digits.isEmpty()) {
                    StringBuilder normalized = new StringBuilder();
                    digits.chars().forEach(c -> normalized.append(Character.digit(c, 10)));
                    where += " OR normalized_phone LIKE ?"; parameters.add(normalized + "%");
                }
            }
            where += ")";
        }
        parameters.add((long) page * 50);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT " + columns(catalog) + " FROM " + catalog.table + where
            + " ORDER BY lower(name), id LIMIT 51 OFFSET ?", parameters.toArray());
        boolean more = rows.size() > 50;
        return new Page(rows.stream().limit(50).map(row -> external(row)).toList(), more);
    }

    private void requireRead(Catalog catalog, AuthUser user) {
        if (user.permissions().contains("SETTINGS_MANAGE")) return;
        boolean allowed = switch (catalog) {
            case PRODUCTS -> user.permissions().contains("SALES_CREATE") || user.permissions().contains("PURCHASES_CREATE") || user.permissions().contains("INVENTORY_ADJUST");
            case PAYMENTS -> user.permissions().contains("CUSTOMER_PAYMENTS_CREATE") || user.permissions().contains("SALES_CREATE") || user.permissions().contains("PURCHASES_CREATE") || user.permissions().contains("EXPENSES_CREATE") || user.permissions().contains("SUPPLIER_PAYMENTS_CREATE");
            case CUSTOMERS -> user.permissions().contains("SALES_CREATE") || user.permissions().contains("CUSTOMER_PAYMENTS_CREATE");
            case CONTAINERS -> user.permissions().contains("SALES_CREATE");
            case SUPPLIERS -> user.permissions().contains("PURCHASES_VIEW") || user.permissions().contains("PURCHASES_CREATE") || user.permissions().contains("SUPPLIER_PAYMENTS_CREATE");
            case EXPENSES -> user.permissions().contains("EXPENSES_CREATE");
        };
        if (!allowed) throw new AccessDeniedException("Missing reference-data capability");
    }

    public Map<String,Object> detail(Catalog catalog, String rawId, AuthUser user) {
        requireRead(catalog,user);
        Object id;
        try { id = catalog == Catalog.PAYMENTS ? Long.valueOf(rawId) : UUID.fromString(rawId); }
        catch (IllegalArgumentException ex) { throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND"); }
        var rows=jdbc.queryForList("SELECT " + columns(catalog) + " FROM " + catalog.table + " WHERE id=?",id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        return external(rows.getFirst());
    }

    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public Map<String,Object> save(Catalog catalog, String rawId, MasterDataInput input, AuthUser user) {
        Object id;
        try { id = rawId == null ? UUID.randomUUID() : catalog == Catalog.PAYMENTS ? Long.valueOf(rawId) : UUID.fromString(rawId); }
        catch (IllegalArgumentException ex) { throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND"); }
        // Serialize default changes and require a current version before changing any other payment method.
        if (catalog == Catalog.PAYMENTS) jdbc.execute("LOCK TABLE payment_methods IN EXCLUSIVE MODE");
        Map<String,Object> before = null;
        if (rawId != null) {
            var records = jdbc.queryForList("SELECT * FROM " + catalog.table + " WHERE id=? FOR UPDATE", id);
            if (records.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND");
            before = records.getFirst();
            if (((Number)before.get("version")).longValue() != input.version())
                throw new ApiException(HttpStatus.CONFLICT, "EDIT_CONFLICT");
            if (Boolean.TRUE.equals(before.get("is_walk_in"))) fail("WALK_IN_PROTECTED");
        } else if (input.version() != 0) fail("VALIDATION_FAILED");

        var values = values(catalog, input);
        if (catalog == Catalog.PRODUCTS && before != null && !Objects.equals(before.get("tracking_type"),input.trackingType())
            && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM purchase_items WHERE product_id=?) OR EXISTS(SELECT 1 FROM sale_items WHERE product_id=?) OR EXISTS(SELECT 1 FROM waste_records WHERE product_id=?)",Boolean.class,id,id,id)))
            fail("PRODUCT_UNIT_LOCKED");
        if (catalog == Catalog.PAYMENTS) {
            boolean requestedDefault = Boolean.TRUE.equals(input.isDefault());
            if (requestedDefault && !input.active()) fail("DEFAULT_MUST_BE_ACTIVE");
            if (before != null && Boolean.TRUE.equals(before.get("is_default")) && !requestedDefault)
                fail("DEFAULT_REQUIRED");
            if (requestedDefault) {
                // Record implicit default changes as well as the directly edited record.
                var defaults = jdbc.queryForList("SELECT id FROM payment_methods WHERE is_default AND id <> ?", rawId == null ? -1L : id);
                for (var old : defaults) {
                    String previousId = old.get("id").toString();
                    String oldJson = snapshot(catalog, old.get("id"));
                    jdbc.update("UPDATE payment_methods SET is_default=false, version=version+1 WHERE id=?", old.get("id"));
                    audit(catalog, previousId, user.id(), oldJson, snapshot(catalog, old.get("id")));
                }
            }
        }
        String oldJson = rawId == null ? null : snapshot(catalog, id);
        if (rawId == null) {
            if (catalog != Catalog.PAYMENTS) values.put("id", id);
            String columns = String.join(",", values.keySet());
            String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
            if (catalog == Catalog.PAYMENTS) id = jdbc.queryForObject("INSERT INTO payment_methods(" + columns + ") VALUES (" + placeholders + ") RETURNING id", Long.class, values.values().toArray());
            else jdbc.update("INSERT INTO " + catalog.table + "(" + columns + ") VALUES (" + placeholders + ")", values.values().toArray());
        } else {
            var args = new ArrayList<>(values.values()); args.add(id);
            jdbc.update("UPDATE " + catalog.table + " SET " + String.join(",", values.keySet().stream().map(key -> key + "=?").toList())
                + ",version=version+1 WHERE id=?", args.toArray());
        }
        audit(catalog, id.toString(), user.id(), oldJson, snapshot(catalog, id));
        return external(jdbc.queryForMap("SELECT " + columns(catalog) + " FROM " + catalog.table + " WHERE id=?", id));
    }

    private LinkedHashMap<String,Object> values(Catalog catalog, MasterDataInput in) {
        var values = new LinkedHashMap<String,Object>();
        values.put("name", in.name().strip()); values.put("active", in.active());
        switch (catalog) {
            case PRODUCTS -> {
                if (in.code() == null || in.code().isBlank() || in.price() == null
                    || !Set.of("SPICE","HONEY","OTHER").contains(Objects.toString(in.category(), ""))
                    || !Set.of("WEIGHT","PIECE").contains(Objects.toString(in.trackingType(), ""))) fail("VALIDATION_FAILED");
                if (("SPICE".equals(in.category()) && !"WEIGHT".equals(in.trackingType()))
                    || ("HONEY".equals(in.category()) && !"PIECE".equals(in.trackingType()))) fail("INVALID_TRACKING_TYPE");
                values.put("code", in.code().strip()); values.put("category", in.category());
                values.put("tracking_type", in.trackingType()); values.put("default_selling_price", in.price());
                values.put("notes", blank(in.notes()));
            }
            case CUSTOMERS, SUPPLIERS -> {
                values.put("phone", blank(in.phone())); values.put("address", blank(in.address())); values.put("notes", blank(in.notes()));
                if (catalog == Catalog.CUSTOMERS) values.put("normalized_phone", PhoneNormalizer.normalize(in.phone()));
            }
            case CONTAINERS -> {
                if (in.price() == null) fail("VALIDATION_FAILED");
                values.put("default_price", in.price()); values.put("notes", blank(in.notes()));
            }
            case PAYMENTS -> {
                if (in.name().strip().length() > 100) fail("VALIDATION_FAILED");
                values.put("is_default", Boolean.TRUE.equals(in.isDefault()));
            }
            case EXPENSES -> { }
        }
        return values;
    }
    private String columns(Catalog c) {
        return switch (c) {
            case PRODUCTS -> "id,name,code,category,tracking_type,default_selling_price,notes,active,version";
            case CUSTOMERS -> "id,name,phone,address,notes,is_walk_in,active,version";
            case SUPPLIERS -> "id,name,phone,address,notes,active,version";
            case CONTAINERS -> "id,name,default_price,notes,active,version";
            case PAYMENTS -> "id,name,is_default,active,version";
            case EXPENSES -> "id,name,active,version";
        };
    }
    private Map<String,Object> external(Map<String,Object> row) {
        var result = new LinkedHashMap<>(row);
        // Decimal prices remain strings across the JSON boundary; no floating-point accounting.
        for (String key : List.of("default_price", "default_selling_price")) {
            if (result.containsKey(key)) result.put(key, result.get(key).toString());
        }
        result.put("id", result.get("id").toString());
        return result;
    }
    private String snapshot(Catalog c, Object id) {
        return jdbc.queryForObject("SELECT row_to_json(r)::text FROM (SELECT * FROM " + c.table + " WHERE id=?) r", String.class, id);
    }
    private void audit(Catalog c, String id, UUID userId, String before, String after) {
        jdbc.update("INSERT INTO master_data_audit(user_id,entity_type,entity_id,action,old_values,new_values) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
            userId,c.path,id,before == null ? "CREATE" : "UPDATE",before,after);
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static void fail(String code) { throw new ApiException(HttpStatus.BAD_REQUEST, code); }
}
