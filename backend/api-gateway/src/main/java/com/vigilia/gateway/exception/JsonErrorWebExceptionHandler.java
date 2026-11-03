package com.vigilia.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Reactive Global Error Handler converting Gateway exceptions into standard JSON error envelopes.
 */
@Component
@Order(-2) // High precedence over default Spring Boot error handlers
public class JsonErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonErrorWebExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public JsonErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status;
        String code;
        String message;

        if (ex instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            code = "UPSTREAM_TIMEOUT";
            message = "Le service en aval n'a pas répondu dans le délai imparti";
        } else if (ex instanceof ConnectException || ex.getMessage() != null && ex.getMessage().contains("Connection refused")) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            code = "SERVICE_UNAVAILABLE";
            message = "Le service en aval est actuellement indisponible";
        } else if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            code = status.is4xxClientError() ? "CLIENT_ERROR" : "GATEWAY_ERROR";
            message = rse.getReason() != null ? rse.getReason() : ex.getMessage();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL_SERVER_ERROR";
            message = "Une erreur inattendue est survenue au niveau de l'API Gateway";
        }

        String correlationId = (String) exchange.getAttributes().getOrDefault("X-Correlation-Id", "");

        log.error("Erreur gérée au niveau de l'API Gateway [status={}, code={}, correlationId={}]: {}",
                status.value(), code, correlationId, ex.getMessage());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (!correlationId.isBlank()) {
            response.getHeaders().set("X-Correlation-Id", correlationId);
        }

        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("code", code);
        errorMap.put("message", message);
        errorMap.put("status", status.value());
        errorMap.put("correlationId", correlationId);
        errorMap.put("timestamp", Instant.now().toString());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorMap);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
