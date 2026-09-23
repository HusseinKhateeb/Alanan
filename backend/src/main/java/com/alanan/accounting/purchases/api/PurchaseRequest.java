package com.alanan.accounting.purchases.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseRequest(
    @NotNull UUID requestId,
    @NotNull UUID supplierId,
    @NotNull @PastOrPresent LocalDate invoiceDate,
    @Size(max=200) String supplierReference,
    @Size(max=2000) String notes,
    @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal paidNow,
    @Positive Long paymentMethodId,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items
) {
    public record Item(@NotNull UUID productId,
                       @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=3) BigDecimal quantity,
                       @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=6) BigDecimal unitCost) {}
}
