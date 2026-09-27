package com.alanan.accounting.orders;

import com.alanan.accounting.sales.api.SaleRequest;
import com.alanan.accounting.sales.api.SaleRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OrderRequest(
    @NotNull UUID requestId, UUID customerId,
    @NotNull LocalDate invoiceDate, @Size(max=2000) String notes,
    @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal paidNow,
    @Positive Long paymentMethodId,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items
) {
    public SaleRequest asSaleRequest() {
        return new SaleRequest(requestId,customerId,invoiceDate,notes,paidNow,paymentMethodId,items);
    }
}
