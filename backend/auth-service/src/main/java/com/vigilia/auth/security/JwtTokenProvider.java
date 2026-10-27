package com.vigilia.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Service generating and validating RS256-signed JSON Web Tokens.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final RsaKeyProvider rsaKeyProvider;
    private final String issuer;
    private final long expirationMs;

    public JwtTokenProvider(
            RsaKeyProvider rsaKeyProvider,
            @Value("${jwt.issuer:vigilia-auth}") String issuer,
            @Value("${jwt.expiration:900000}") long expirationMs) { // 900 000 ms = 15 minutes default
        this.rsaKeyProvider = rsaKeyProvider;
        this.issuer = issuer;
        this.expirationMs = expirationMs;
    }

    public String generateAccessToken(UUID userId, String email, String role) {
        Instant now = Instant.now();
        Instant exp = now.plusMillis(expirationMs);

        return Jwts.builder()
                .header()
                .keyId(rsaKeyProvider.getKeyId())
                .type("JWT")
                .and()
                .subject(userId.toString())
                .issuer(issuer)
                .claim("email", email)
                .claim("role", role)
                .claim("roles", List.of("ROLE_" + role))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(rsaKeyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(rsaKeyProvider.getPublicKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Jeton JWT RS256 invalide ou expiré : {}", e.getMessage());
            return false;
        }
    }

    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(rsaKeyProvider.getPublicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUserId(String token) {
        return getClaims(token).getSubject();
    }

    public String getEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}
