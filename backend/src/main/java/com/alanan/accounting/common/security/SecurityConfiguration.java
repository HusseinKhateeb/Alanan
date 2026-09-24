package com.alanan.accounting.common.security;

import com.alanan.accounting.auth.IdentityRepository;
import com.alanan.accounting.auth.RefreshSessions;
import com.alanan.accounting.common.exception.ApiErrors;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityRepository identities, RefreshSessions sessions, ApiErrors errors) throws Exception {
        return http
            // Access tokens are only accepted through Authorization headers; refresh cookies work only at /api/auth/refresh.
            .csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/logout-current", "/api/auth/language", "/api/auth/password", "/api/users", "/api/users/options", "/api/users/*", "/api/users/*/access").authenticated()
                .requestMatchers("/api/purchases", "/api/purchases/*", "/api/purchases/*/cancel", "/api/purchases/*/pdf", "/api/supplier-balances", "/api/inventory", "/api/inventory/movements", "/api/inventory/adjustments").authenticated()
                .requestMatchers("/api/expenses", "/api/expenses/*", "/api/expenses/*/cancel", "/api/waste", "/api/waste/*").authenticated()
                .requestMatchers("/api/audit").authenticated()
                .requestMatchers("/api/backups", "/api/backups/*", "/api/backups/*/download", "/api/backups/*/checksum", "/api/backups/*/restore-test", "/api/backups/exports").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,"/api/admin/deletions/*/*").authenticated()
                .requestMatchers("/api/dashboard").authenticated()
                .requestMatchers("/api/reports/*", "/api/reports/*/csv").authenticated()
                .requestMatchers("/api/customer-payments", "/api/customer-payments/*", "/api/customer-payments/*/cancel").authenticated()
                .requestMatchers("/api/supplier-payments", "/api/supplier-payments/*", "/api/supplier-payments/*/cancel").authenticated()
                .requestMatchers("/api/sales", "/api/sales/*", "/api/sales/*/cancel", "/api/sales/*/pdf", "/api/customer-balances").authenticated()
                .requestMatchers("/api/products", "/api/products/*", "/api/customers", "/api/customers/*",
                    "/api/suppliers", "/api/suppliers/*", "/api/container-types", "/api/container-types/*",
                    "/api/payment-methods", "/api/payment-methods/*", "/api/expense-categories", "/api/expense-categories/*").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> errors.write(req, res, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((req, res, e) -> errors.write(req, res, 403, "FORBIDDEN")))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint((req, res, e) -> errors.write(req, res, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((req, res, e) -> errors.write(req, res, 403, "FORBIDDEN"))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    try {
                        Number version = token.getClaim("ver");
                        if (version == null) throw new InvalidBearerTokenException("Invalid token");
                        UUID userId = UUID.fromString(token.getSubject());
                        String sessionClaim = token.getClaimAsString("sid");
                        if (sessionClaim == null) throw new InvalidBearerTokenException("Invalid session");
                        UUID sessionId = UUID.fromString(sessionClaim);
                        if (!sessions.active(sessionId, userId)) throw new InvalidBearerTokenException("Invalid session");
                        var user = identities.activeUser(userId, version.longValue())
                            .orElseThrow(() -> new InvalidBearerTokenException("Invalid token"));
                        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
                        user.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                        user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                        return new UsernamePasswordAuthenticationToken(user, null, authorities);
                    } catch (IllegalArgumentException | ClassCastException ex) {
                        throw new InvalidBearerTokenException("Invalid token");
                    }
                })))
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKeySpec signingKey(@Value("${app.auth.jwt-secret}") String secret) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(secret); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("JWT_SECRET must be base64 encoded"); }
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKeySpec key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("alanan"));
        return decoder;
    }
}
