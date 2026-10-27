package com.vigilia.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
    }

    @Test
    @DisplayName("Génération automatique de X-Correlation-Id si absent")
    void testGenerateCorrelationIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(capturedExchange.get());
        String generatedId = capturedExchange.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(generatedId);
        assertFalse(generatedId.isBlank());
        assertEquals(generatedId, exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Préservation du X-Correlation-Id valide fourni par le client")
    void testPreserveValidCorrelationId() {
        String validId = "custom-trace-uuid-12345";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/me")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, validId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(validId, capturedExchange.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
        assertEquals(validId, exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Anti-usurpation : suppression des en-têtes d'identité client falsifiés")
    void testStripSpoofableIdentityHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/transactions")
                .header("X-User-Id", "attacker-uuid")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Roles", "ROLE_ADMIN")
                .header("X-Authenticated-User", "admin")
                .header("X-Customer-Id", "cust-999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertNotNull(capturedExchange.get());
        assertNull(capturedExchange.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(capturedExchange.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertNull(capturedExchange.get().getRequest().getHeaders().getFirst("X-User-Roles"));
        assertNull(capturedExchange.get().getRequest().getHeaders().getFirst("X-Authenticated-User"));
        assertNull(capturedExchange.get().getRequest().getHeaders().getFirst("X-Customer-Id"));
    }
}
