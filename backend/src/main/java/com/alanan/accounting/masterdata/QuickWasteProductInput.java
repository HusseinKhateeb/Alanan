package com.alanan.accounting.masterdata;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record QuickWasteProductInput(
    @NotBlank @Size(max=200) String name,
    @NotBlank @Size(max=60) String code,
    @NotBlank String category,
    @NotBlank String trackingType,
    @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=16, fraction=2) BigDecimal price
) {}
