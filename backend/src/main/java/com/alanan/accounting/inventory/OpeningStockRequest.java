package com.alanan.accounting.inventory;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OpeningStockRequest(
    @NotNull UUID requestId,
    @NotNull UUID productId,
    @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=12, fraction=3) BigDecimal quantity,
    @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=12, fraction=6) BigDecimal unitCost,
    @NotNull @PastOrPresent LocalDate recordedDate,
    @NotBlank @Size(max=500) String reason
) {}
