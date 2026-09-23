package com.alanan.accounting.purchases.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelPurchaseRequest(@NotBlank @Size(max=500) String reason, boolean refundReceivedConfirmed, Long refundMethodId) {}
