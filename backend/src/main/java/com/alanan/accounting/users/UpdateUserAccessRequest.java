package com.alanan.accounting.users;

import jakarta.validation.constraints.*;
import java.util.List;

public record UpdateUserAccessRequest(
    @NotEmpty @Size(max=10) List<@NotBlank String> roles,
    @NotNull @Size(max=100) List<@NotBlank String> permissions
) {}
