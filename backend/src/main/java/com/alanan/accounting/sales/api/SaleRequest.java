package com.alanan.accounting.sales.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record SaleRequest(
    @NotNull UUID requestId, UUID customerId,
    @NotNull @PastOrPresent LocalDate invoiceDate, @Size(max=2000) String notes,
    @NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal paidNow,
    @Positive Long paymentMethodId,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items
) {
    public enum Mode { BY_WEIGHT, BY_AMOUNT, BY_PIECE }
    public record Item(UUID productId, UUID containerTypeId, @NotNull Mode mode,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=3) BigDecimal quantity,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal requestedAmount,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=2) BigDecimal unitPrice) {}
}
