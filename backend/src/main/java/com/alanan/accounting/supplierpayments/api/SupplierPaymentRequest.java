package com.alanan.accounting.supplierpayments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SupplierPaymentRequest(
    @NotNull UUID requestId,
    @NotNull UUID supplierId,
    @NotNull @PastOrPresent LocalDate paymentDate,
    @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal amount,
    @Positive Long paymentMethodId,
    @Size(max=2000) String notes,
    @NotNull Mode mode,
    @NotNull @Size(max=100) List<@NotNull @Valid Allocation> allocations
) {
    public enum Mode { MANUAL, AUTO }
    public record Allocation(@NotNull UUID invoiceId,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal amount) {}
}
