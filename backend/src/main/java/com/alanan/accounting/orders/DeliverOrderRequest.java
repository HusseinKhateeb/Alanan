package com.alanan.accounting.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;

public record DeliverOrderRequest(@NotNull Long version, @NotNull @PastOrPresent LocalDate deliveryDate) {}
