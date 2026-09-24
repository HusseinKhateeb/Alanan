package com.alanan.accounting.purchases.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CorrectPurchaseRequest(
    @NotNull @PositiveOrZero Long version,
    @NotNull @PastOrPresent LocalDate invoiceDate,
    @Size(max=200) String supplierReference,
    @Size(max=2000) String notes,
    @NotBlank @Size(max=500) String reason,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items
) {
    public record Item(@NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=12,fraction=3) BigDecimal quantity,
                       @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=12,fraction=6) BigDecimal unitCost) {}
}
