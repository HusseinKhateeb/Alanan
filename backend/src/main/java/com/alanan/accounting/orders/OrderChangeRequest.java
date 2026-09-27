package com.alanan.accounting.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record OrderChangeRequest(@NotNull Long version, @NotNull @Valid OrderRequest sale) {}
