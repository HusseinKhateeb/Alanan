package com.alanan.accounting.common.security;

import com.alanan.accounting.auth.IdentityRepository;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityRepository identities, ApiErrors errors) throws Exception {
        return http
            // Tokens are only accepted through Authorization headers, never cookies or query parameters.
            .csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/language", "/api/users").authenticated()
                .requestMatchers("/api/purchases", "/api/purchases/*", "/api/purchases/*/cancel", "/api/purchases/*/pdf", "/api/supplier-balances", "/api/inventory", "/api/inventory/movements").authenticated()
                .requestMatchers("/api/expenses", "/api/expenses/*", "/api/expenses/*/cancel", "/api/waste", "/api/waste/*").authenticated()
                .requestMatchers("/api/audit").authenticated()
                .requestMatchers("/api/dashboard").authenticated()
                .requestMatchers("/api/reports/*").authenticated()
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
                        var user = identities.activeUser(UUID.fromString(token.getSubject()), version.longValue())
                            .orElseThrow(() -> new InvalidBearerTokenException("Invalid token"));
                        return new UsernamePasswordAuthenticationToken(user, null,
                            user.permissions().stream().map(SimpleGrantedAuthority::new).toList());
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
