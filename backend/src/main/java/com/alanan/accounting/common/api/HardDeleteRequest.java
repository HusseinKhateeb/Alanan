package com.alanan.accounting.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HardDeleteRequest(@NotBlank @Size(max=500) String reason) {}
