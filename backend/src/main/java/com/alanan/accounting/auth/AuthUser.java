package com.alanan.accounting.auth;

import java.util.List;
import java.util.UUID;

public record AuthUser(UUID id, String username, String displayName, String preferredLanguage,
                       List<String> permissions, List<String> roles) {}
