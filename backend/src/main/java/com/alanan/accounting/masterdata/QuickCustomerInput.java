package com.alanan.accounting.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuickCustomerInput(@NotBlank @Size(max=200) String name, @Size(max=60) String phone) {}
