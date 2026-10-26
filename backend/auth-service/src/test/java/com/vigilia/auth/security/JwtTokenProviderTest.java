package com.vigilia.auth.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private RsaKeyProvider rsaKeyProvider;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        rsaKeyProvider = new RsaKeyProvider("", "", "test-key-id");
        jwtTokenProvider = new JwtTokenProvider(rsaKeyProvider, "vigilia-auth", 900000);
    }

    @Test
    @DisplayName("Génération et validation d'un JWT RS256 avec claims valides")
    void testGenerateAndValidateToken() {
        UUID userId = UUID.randomUUID();
        String email = "sara@example.com";
        String role = "CUSTOMER";

        String token = jwtTokenProvider.generateAccessToken(userId, email, role);

        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));

        Claims claims = jwtTokenProvider.getClaims(token);
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("vigilia-auth", claims.getIssuer());
        assertEquals(email, claims.get("email", String.class));
        assertEquals(role, claims.get("role", String.class));

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertNotNull(roles);
        assertTrue(roles.contains("ROLE_CUSTOMER"));
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    @DisplayName("Rejet d'un jeton altéré ou falsifié")
    void testTamperedToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(userId, "sara@example.com", "CUSTOMER");

        String tamperedToken = token + "corrupted";
        assertFalse(jwtTokenProvider.validateToken(tamperedToken));
    }

    @Test
    @DisplayName("Format JWKS RFC 7517 avec kid, n et e")
    void testJwksFormat() {
        Map<String, Object> jwks = rsaKeyProvider.getJwks();

        assertNotNull(jwks);
        assertTrue(jwks.containsKey("keys"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        assertEquals(1, keys.size());

        Map<String, Object> key = keys.get(0);
        assertEquals("RSA", key.get("kty"));
        assertEquals("sig", key.get("use"));
        assertEquals("RS256", key.get("alg"));
        assertEquals("test-key-id", key.get("kid"));
        assertNotNull(key.get("n"));
        assertNotNull(key.get("e"));
    }
}
