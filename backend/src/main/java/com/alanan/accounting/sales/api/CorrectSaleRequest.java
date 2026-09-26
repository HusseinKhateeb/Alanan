package com.alanan.accounting.sales.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.alanan.accounting.sales.api.SaleRequest.Item;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CorrectSaleRequest(
    @NotNull @PositiveOrZero Long version,
    UUID customerId,
    @NotNull @PastOrPresent LocalDate invoiceDate,
    @Size(max=2000) String notes,
    @NotNull @DecimalMin("0") @Digits(integer=16, fraction=2) BigDecimal paidNow,
    @Positive Long paymentMethodId,
    @NotBlank @Size(max=500) String reason,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items
) {}
