package com.alanan.accounting.sales.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelSaleRequest(@NotBlank @Size(max=500) String reason, boolean refundConfirmed, Long refundMethodId) {}
