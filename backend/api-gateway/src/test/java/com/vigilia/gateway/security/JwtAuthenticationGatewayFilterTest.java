package com.vigilia.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthenticationGatewayFilterTest {

    private JwksClient jwksClient;
    private JwtAuthenticationGatewayFilter filter;
    private ObjectMapper objectMapper;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private final String keyId = "test-kid-1";
    private final String issuer = "vigilia-auth";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.publicKey = (RSAPublicKey) kp.getPublic();

        this.objectMapper = new ObjectMapper();
        this.jwksClient = new JwksClient(WebClient.builder(), "http://localhost:8081/.well-known/jwks.json", "", objectMapper);
        this.jwksClient.cacheKey(keyId, publicKey);

        this.filter = new JwtAuthenticationGatewayFilter(jwksClient, objectMapper, issuer);
    }

    private String createToken(UUID userId, String email, String role, List<String> roles, String iss, Instant expiration) {
        return Jwts.builder()
                .header()
                .keyId(keyId)
                .type("JWT")
                .and()
                .subject(userId.toString())
                .issuer(iss)
                .claim("email", email)
                .claim("role", role)
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiration))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    @Test
    @DisplayName("Route publique autorisée sans jeton d'accès")
    void testPublicRouteAllowedWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNull(exchange.getResponse().getStatusCode()); // Not intercepted
    }

    @Test
    @DisplayName("Blocage strict de toute tentative d'accès à une route interne")
    void testInternalOnlyRouteStrictlyBlocked() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/internal/risk/evaluate").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Sécurisation Actuator : /actuator/health rejeté 403 FORBIDDEN via Gateway")
    void testActuatorHealthForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Sécurisation Actuator : /actuator/prometheus rejeté 403 FORBIDDEN via Gateway")
    void testActuatorPrometheusForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/prometheus").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Sécurisation Actuator : /actuator racine rejeté 403 FORBIDDEN via Gateway")
    void testActuatorRootForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Route fallback autorisée sans jeton d'authentification pour invocation interne")
    void testFallbackRouteAllowedWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/fallback/customer").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNull(exchange.getResponse().getStatusCode()); // Not intercepted
    }

    @Test
    @DisplayName("Route protégée sans jeton d'authentification : rejet 401 UNAUTHORIZED")
    void testProtectedRouteMissingToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Route protégée avec JWT RS256 valide : autorisation et injection des en-têtes d'identité")
    void testProtectedRouteValidToken() {
        UUID userId = UUID.randomUUID();
        String token = createToken(userId, "sara@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(downstreamExchange.get());
        assertEquals(userId.toString(), downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("CUSTOMER", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("ROLE_CUSTOMER", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Roles"));
        assertEquals("sara@example.com", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Email"));
    }

    @Test
    @DisplayName("Jeton JWT expiré : rejet 401 TOKEN_EXPIRED")
    void testExpiredToken() {
        UUID userId = UUID.randomUUID();
        String expiredToken = createToken(userId, "sara@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), issuer, Instant.now().minus(5, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Jeton JWT falsifié ou à mauvaise signature : rejet 401 TOKEN_INVALID")
    void testTamperedToken() {
        UUID userId = UUID.randomUUID();
        String validToken = createToken(userId, "sara@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));
        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "abcde";

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Jeton avec mauvais émetteur (issuer mismatch) : rejet 401 TOKEN_INVALID")
    void testInvalidIssuerToken() {
        UUID userId = UUID.randomUUID();
        String wrongIssuerToken = createToken(userId, "sara@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), "rogue-auth-server", Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuerToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("RBAC : Rôle CUSTOMER tente d'accéder à la file analyste /api/v1/risk/reviews -> 403 FORBIDDEN")
    void testRbacCustomerForbiddenOnAnalystRoute() {
        UUID userId = UUID.randomUUID();
        String token = createToken(userId, "cust@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/risk/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("RBAC : Rôle ANALYST accède à la file analyste /api/v1/risk/reviews -> Autorisé")
    void testRbacAnalystAllowedOnAnalystRoute() {
        UUID userId = UUID.randomUUID();
        String token = createToken(userId, "analyst@example.com", "ANALYST", List.of("ROLE_ANALYST"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/risk/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(downstreamExchange.get());
        assertEquals("ANALYST", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("ROLE_ANALYST", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Roles"));
    }

    @Test
    @DisplayName("RBAC : Rôle CUSTOMER tente d'accéder aux politiques /api/v1/risk/policies/active -> 403 FORBIDDEN")
    void testRbacCustomerForbiddenOnPoliciesRoute() {
        UUID userId = UUID.randomUUID();
        String token = createToken(userId, "cust@example.com", "CUSTOMER", List.of("ROLE_CUSTOMER"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/risk/policies/active")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("RBAC : Rôle ANALYST accède aux politiques /api/v1/risk/policies/active -> Autorisé")
    void testRbacAnalystAllowedOnPoliciesRoute() {
        UUID userId = UUID.randomUUID();
        String token = createToken(userId, "analyst@example.com", "ANALYST", List.of("ROLE_ANALYST"), issuer, Instant.now().plus(15, ChronoUnit.MINUTES));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/risk/policies/active")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(downstreamExchange.get());
        assertEquals("ANALYST", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("ROLE_ANALYST", downstreamExchange.get().getRequest().getHeaders().getFirst("X-User-Roles"));
    }
}
