package com.alanan.accounting.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    public TokenService(JwtEncoder encoder) { this.encoder = encoder; }
    public record Token(String accessToken, Instant expiresAt, AuthUser user) {
        @Override public String toString() { return "Token[REDACTED]"; }
    }
    public Token issue(AuthUser user, long version, UUID sessionId) {
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(1800);
        var claims = JwtClaimsSet.builder().issuer("alanan").subject(user.id().toString())
            .issuedAt(now).expiresAt(expires).id(UUID.randomUUID().toString())
            .claim("ver", version).claim("sid", sessionId.toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Token(token, expires, user);
    }
}
