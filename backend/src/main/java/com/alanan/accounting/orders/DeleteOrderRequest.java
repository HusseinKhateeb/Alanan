package com.alanan.accounting.orders;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DeleteOrderRequest(@NotNull Long version, @NotBlank @Size(max=500) String reason) {}
