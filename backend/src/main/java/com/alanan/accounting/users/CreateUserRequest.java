package com.alanan.accounting.users;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateUserRequest(
    @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{3,100}") String username,
    @NotBlank @Size(max=200) String displayName,
    @NotBlank String password,
    @NotBlank @Pattern(regexp="ar|en") String preferredLanguage,
    @NotNull Boolean active,
    @NotEmpty @Size(max=10) List<@NotBlank String> roles,
    @NotNull @Size(max=100) List<@NotBlank String> permissions
) {
    @Override public String toString() { return "CreateUserRequest[REDACTED]"; }
}
