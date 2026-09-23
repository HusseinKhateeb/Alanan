package com.alanan.accounting.common.api;

import com.alanan.accounting.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Bounded read projections with decimal values preserved across JSON serialization. */
public final class PagedRows {
    private PagedRows() {}
    public record Page(List<Map<String,Object>> items, boolean hasMore) {}
    public static Page page(List<Map<String,Object>> rows) { return new Page(rows.stream().limit(50).map(PagedRows::external).toList(),rows.size()>50); }
    public static void validPage(int page) { if(page<0) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED"); }
    public static Map<String,Object> external(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        row.forEach((key,value)->result.put(key,value instanceof BigDecimal decimal ? decimal.toPlainString() : value instanceof UUID ? value.toString() : value));
        return result;
    }
}
