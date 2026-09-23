package com.alanan.accounting.waste;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record WasteRequest(@NotNull UUID requestId,@NotNull UUID productId,
    @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=3) BigDecimal quantity,
    @NotNull @PastOrPresent LocalDate wasteDate,@NotBlank @Size(max=500) String reason,@Size(max=2000) String notes) {}
