package com.alanan.accounting.masterdata;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record MasterDataInput(
    @NotBlank @Size(max=200) String name,
    @Size(max=60) String code,
    @Size(max=10) String category,
    @Size(max=10) String trackingType,
    @DecimalMin(value="0.00", inclusive=false) @Digits(integer=16, fraction=2) BigDecimal price,
    @Size(max=60) String phone,
    @Size(max=500) String address,
    @Size(max=2000) String notes,
    @NotNull Boolean active,
    Boolean isDefault,
    @NotNull @Min(0) Long version
) {}
