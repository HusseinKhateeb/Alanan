package com.alanan.accounting.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuickExpenseCategoryInput(@NotBlank @Size(max=200) String name) {}
