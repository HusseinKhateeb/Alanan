package com.alanan.accounting.orders;

import com.alanan.accounting.sales.api.SaleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record OrderChangeRequest(@NotNull Long version, @NotNull @Valid SaleRequest sale) {}
