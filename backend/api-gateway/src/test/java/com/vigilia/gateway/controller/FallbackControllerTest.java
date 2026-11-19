package com.vigilia.gateway.controller;

import com.vigilia.gateway.exception.ApiErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class FallbackControllerTest {

    private FallbackController fallbackController;

    @BeforeEach
    void setUp() {
        fallbackController = new FallbackController();
    }

    @Test
    @DisplayName("Fallback Auth Service : HTTP 503, envelope normalisée, correlationId propagé")
    void testAuthFallback() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/fallback/auth")
                .header("X-Correlation-Id", "corr-auth-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("X-Correlation-Id", "corr-auth-123");

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.authFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("corr-auth-123", response.getHeaders().getFirst("X-Correlation-Id"));
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SERVICE_UNAVAILABLE", body.getCode());
                    assertEquals(503, body.getStatus());
                    assertEquals("corr-auth-123", body.getCorrelationId());
                    assertNotNull(body.getTimestamp());
                    assertTrue(body.getMessage().contains("authentification"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback Customer Service : HTTP 503, enveloppe normalisée")
    void testCustomerFallback() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/fallback/customer")
                .header("X-Correlation-Id", "corr-cust-456")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.customerFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("corr-cust-456", response.getHeaders().getFirst("X-Correlation-Id"));
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SERVICE_UNAVAILABLE", body.getCode());
                    assertEquals(503, body.getStatus());
                    assertEquals("corr-cust-456", body.getCorrelationId());
                    assertTrue(body.getMessage().contains("client"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback Transaction Service : HTTP 503, pas de fausses données de succès financier")
    void testTransactionFallback() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/fallback/transaction")
                .header("X-Correlation-Id", "corr-txn-789")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.transactionFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SERVICE_UNAVAILABLE", body.getCode());
                    assertEquals(503, body.getStatus());
                    assertEquals("corr-txn-789", body.getCorrelationId());
                    assertTrue(body.getMessage().contains("transactions"));
                    assertTrue(body.getMessage().contains("Aucune transaction n'a été exécutée"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback Risk Service : HTTP 503, enveloppe normalisée")
    void testRiskFallback() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/fallback/risk")
                .header("X-Correlation-Id", "corr-risk-101")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.riskFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SERVICE_UNAVAILABLE", body.getCode());
                    assertEquals(503, body.getStatus());
                    assertEquals("corr-risk-101", body.getCorrelationId());
                    assertTrue(body.getMessage().contains("risque"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback ML Visualization : HTTP 503, enveloppe normalisée, correlationId propagé")
    void testMlVisualizationFallback() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/fallback/ml-visualization")
                .header("X-Correlation-Id", "corr-viz-202")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.mlVisualizationFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("corr-viz-202", response.getHeaders().getFirst("X-Correlation-Id"));
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SERVICE_UNAVAILABLE", body.getCode());
                    assertEquals(503, body.getStatus());
                    assertEquals("corr-viz-202", body.getCorrelationId());
                    assertTrue(body.getMessage().contains("visualisation ML"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Génération automatique de correlationId si absent")
    void testFallbackGeneratesCorrelationIdIfAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/fallback/auth").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<ResponseEntity<ApiErrorResponse>> result = fallbackController.authFallback(exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    String corr = response.getHeaders().getFirst("X-Correlation-Id");
                    assertNotNull(corr);
                    assertFalse(corr.isBlank());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(corr, body.getCorrelationId());
                })
                .verifyComplete();
    }
}
