package com.alanan.accounting.expenses;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseRequest(@NotNull UUID requestId,@NotNull UUID categoryId,
    @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal amount,
    @NotNull @PastOrPresent LocalDate expenseDate,@Positive Long paymentMethodId,@Size(max=2000) String notes) {}
