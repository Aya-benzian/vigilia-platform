package com.vigilia.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class JsonErrorWebExceptionHandlerTest {

    private JsonErrorWebExceptionHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new JsonErrorWebExceptionHandler(objectMapper);
    }

    @Test
    @DisplayName("Timeout downstream -> 504 GATEWAY_TIMEOUT avec code UPSTREAM_TIMEOUT")
    void testHandleTimeoutException() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/customers/123").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("X-Correlation-Id", "corr-test-123");

        TimeoutException ex = new TimeoutException("Response timeout from downstream");

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
        assertEquals("corr-test-123", exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    @DisplayName("Connexion refusée downstream -> 503 SERVICE_UNAVAILABLE avec code SERVICE_UNAVAILABLE")
    void testHandleConnectException() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/transactions").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("X-Correlation-Id", "corr-test-456");

        ConnectException ex = new ConnectException("Connection refused");

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("corr-test-456", exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }
}
