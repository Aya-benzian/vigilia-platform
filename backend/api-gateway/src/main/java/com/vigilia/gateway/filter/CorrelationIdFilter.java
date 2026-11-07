package com.vigilia.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GlobalFilter managing Correlation ID generation/propagation and stripping spoofed identity headers.
 * Also injects the correlationId into SLF4J MDC for structured JSON logging.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    // Headers that must be stripped to prevent client-side identity spoofing
    private static final String[] SPOOFABLE_HEADERS = {
            "X-User-Id",
            "X-User-Role",
            "X-User-Roles",
            "X-User-Email",
            "X-Authenticated-User",
            "X-Customer-Id"
    };

    @Override
    public int getOrder() {
        // High precedence: run before routing and authentication
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. Resolve or generate valid Correlation ID
        String incomingCorrelationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId;

        if (incomingCorrelationId != null && !incomingCorrelationId.isBlank()
                && VALID_CORRELATION_ID_PATTERN.matcher(incomingCorrelationId.trim()).matches()) {
            correlationId = incomingCorrelationId.trim();
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        // 2. Anti-spoofing: Strip untrusted identity headers from client request
        ServerHttpRequest.Builder requestBuilder = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId);

        for (String headerName : SPOOFABLE_HEADERS) {
            if (request.getHeaders().containsKey(headerName)) {
                log.warn("En-tête d'identité client falsifiable supprimé [header={}, correlationId={}]", headerName, correlationId);
                requestBuilder.headers(httpHeaders -> httpHeaders.remove(headerName));
            }
        }

        ServerHttpRequest mutatedRequest = requestBuilder.build();

        // 3. Attach Correlation ID to response header
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        // Store correlationId in exchange attributes for subsequent filters and error handlers
        exchange.getAttributes().put(CORRELATION_ID_HEADER, correlationId);

        // 4. Inject correlationId into MDC for structured JSON logging
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFirst(() -> MDC.put(MDC_KEY, correlationId))
                .doFinally(signalType -> MDC.remove(MDC_KEY));
    }
}
