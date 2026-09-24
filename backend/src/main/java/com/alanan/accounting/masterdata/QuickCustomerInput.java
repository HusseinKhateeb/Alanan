package com.alanan.accounting.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record QuickCustomerInput(@NotBlank @Size(max=200) String name, @Size(max=60) String phone, UUID requestId) {}
