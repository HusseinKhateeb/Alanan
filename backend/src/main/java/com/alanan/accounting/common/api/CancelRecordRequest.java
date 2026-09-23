package com.alanan.accounting.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelRecordRequest(@NotBlank @Size(max=500) String reason, boolean refundConfirmed) {}
