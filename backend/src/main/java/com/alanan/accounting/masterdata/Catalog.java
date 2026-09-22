package com.alanan.accounting.masterdata;

import com.alanan.accounting.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.Arrays;

public enum Catalog {
    PRODUCTS("products", "products"), CUSTOMERS("customers", "customers"),
    SUPPLIERS("suppliers", "suppliers"), CONTAINERS("container-types", "container_types"),
    PAYMENTS("payment-methods", "payment_methods"), EXPENSES("expense-categories", "expense_categories");
    public final String path;
    public final String table;
    Catalog(String path, String table) { this.path = path; this.table = table; }
    public static Catalog from(String path) {
        return Arrays.stream(values()).filter(c -> c.path.equals(path)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }
}
