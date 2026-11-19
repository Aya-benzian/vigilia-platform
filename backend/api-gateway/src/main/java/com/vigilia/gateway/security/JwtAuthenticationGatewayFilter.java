package com.vigilia.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GlobalFilter verifying RS256 JWT access tokens, enforcing RBAC roles, and injecting verified identity headers.
 */
@Component
public class JwtAuthenticationGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGatewayFilter.class);

    private final JwksClient jwksClient;
    private final ObjectMapper objectMapper;
    private final String expectedIssuer;

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/.well-known/jwks.json",
            "/api/v1/auth/.well-known/jwks.json",
            "/fallback",
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-ui.html"
    );

    public JwtAuthenticationGatewayFilter(
            JwksClient jwksClient,
            ObjectMapper objectMapper,
            @Value("${jwt.issuer:vigilia-auth}") String expectedIssuer) {
        this.jwksClient = jwksClient;
        this.objectMapper = objectMapper;
        this.expectedIssuer = expectedIssuer;
    }

    @Override
    public int getOrder() {
        // Runs immediately after CorrelationIdFilter (which is HIGHEST_PRECEDENCE)
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. Strictly block access to internal-only endpoints from Gateway
        if (isInternalOnlyPath(path)) {
            log.warn("Tentative d'accès bloquée vers une route interne [path={}]", path);
            return writeErrorResponse(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Accès aux routes internes strictement interdit depuis l'API Gateway");
        }

        // 2. Allow public endpoints without token
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 3. Extract Bearer token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Accès refusé sans jeton d'authentification sur route protégée [path={}]", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Jeton d'accès manquant ou format d'autorisation invalide");
        }

        String token = authHeader.substring(7).trim();
        String kid = extractKidFromTokenHeader(token);

        // 4. Validate RS256 signature using JWKS public key
        return jwksClient.getPublicKey(kid)
                .flatMap(publicKey -> {
                    try {
                        Claims claims = Jwts.parser()
                                .verifyWith(publicKey)
                                .requireIssuer(expectedIssuer)
                                .build()
                                .parseSignedClaims(token)
                                .getPayload();

                        // 5. RBAC role verification
                        String role = claims.get("role", String.class);
                        List<String> roles = resolveRoles(claims, role);

                        if (isRestrictedAnalystPath(path) && !hasPrivilegedRole(role, roles)) {
                            log.warn("Rôle insuffisant pour route protégée [path={}, role={}, roles={}]", path, role, roles);
                            return writeErrorResponse(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                    "Rôle insuffisant pour accéder à cette ressource");
                        }

                        // 6. Anti-spoofing: Inject verified identity headers from claims
                        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                                .header("X-User-Id", claims.getSubject())
                                .header("X-User-Role", role != null ? role : "")
                                .header("X-User-Roles", String.join(",", roles));

                        String email = claims.get("email", String.class);
                        if (email != null && !email.isBlank()) {
                            requestBuilder.header("X-User-Email", email);
                        }

                        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());

                    } catch (ExpiredJwtException ex) {
                        log.warn("Jeton JWT expiré [path={}]: {}", path, ex.getMessage());
                        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                                "Le jeton d'accès a expiré");
                    } catch (JwtException ex) {
                        log.warn("Jeton JWT invalide [path={}]: {}", path, ex.getMessage());
                        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_INVALID",
                                "Signature, structure ou émetteur du jeton invalide");
                    }
                })
                .onErrorResume(ex -> {
                    log.error("Échec de validation de la clé publique JWKS pour le jeton : {}", ex.getMessage());
                    return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_INVALID",
                            "Impossible de valider la clé publique du jeton");
                });
    }

    private boolean isPublicPath(String path) {
        for (String publicPrefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(publicPrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalOnlyPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.trim().toLowerCase();
        return normalized.startsWith("/internal")
                || normalized.contains("/internal/")
                || normalized.startsWith("/api/v1/risk/evaluate")
                || normalized.equals("/actuator")
                || normalized.startsWith("/actuator/");
    }

    private boolean isRestrictedAnalystPath(String path) {
        return path.startsWith("/api/v1/risk/reviews")
                || path.startsWith("/api/v1/risk/policies")
                || path.startsWith("/api/v1/risk/decisions")
                || path.startsWith("/api/v1/admin")
                || path.startsWith("/api/v1/visualization");
    }

    private boolean hasPrivilegedRole(String role, List<String> roles) {
        if ("ANALYST".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role)) {
            return true;
        }
        for (String r : roles) {
            if ("ROLE_ANALYST".equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r)
                    || "ANALYST".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveRoles(Claims claims, String singleRole) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        if (singleRole != null && !singleRole.isBlank()) {
            return List.of("ROLE_" + singleRole.replace("ROLE_", ""));
        }
        return Collections.emptyList();
    }

    private String extractKidFromTokenHeader(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                JsonNode headerNode = objectMapper.readTree(headerJson);
                String kid = headerNode.path("kid").asText(null);
                if (kid != null && !kid.isBlank()) {
                    return kid;
                }
            }
        } catch (Exception e) {
            log.warn("Impossible d'extraire kid du header JWT : {}", e.getMessage());
        }
        return "vigilia-key-1";
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = (String) exchange.getAttributes().getOrDefault("X-Correlation-Id", "");

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", code);
        errorBody.put("message", message);
        errorBody.put("status", status.value());
        errorBody.put("correlationId", correlationId);
        errorBody.put("timestamp", Instant.now().toString());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorBody);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
