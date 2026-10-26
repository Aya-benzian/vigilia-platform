package com.vigilia.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client fetching and caching RSA public keys from the Auth Service JWKS endpoint.
 */
@Component
public class JwksClient {

    private static final Logger log = LoggerFactory.getLogger(JwksClient.class);

    private final WebClient webClient;
    private final String jwksUrl;
    private final ObjectMapper objectMapper;
    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();
    private RSAPublicKey staticFallbackKey;

    public JwksClient(
            WebClient.Builder webClientBuilder,
            @Value("${AUTH_JWKS_URL:${auth.service.jwks-url:http://localhost:8081/.well-known/jwks.json}}") String jwksUrl,
            @Value("${JWT_PUBLIC_KEY:}") String staticPublicKeyPem,
            ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.jwksUrl = jwksUrl;
        this.objectMapper = objectMapper;

        if (staticPublicKeyPem != null && !staticPublicKeyPem.isBlank()) {
            try {
                this.staticFallbackKey = parsePublicKey(staticPublicKeyPem);
                log.info("Clé publique RSA statique de secours chargée avec succès");
            } catch (Exception e) {
                log.warn("Impossible de parser la clé publique statique : {}", e.getMessage());
            }
        }
    }

    /**
     * Resolves an RSAPublicKey by its Key ID (kid) with caching and lazy JWKS fetching.
     */
    public Mono<RSAPublicKey> getPublicKey(String kid) {
        if (kid != null && keyCache.containsKey(kid)) {
            return Mono.just(keyCache.get(kid));
        }

        return fetchJwks()
                .map(keys -> {
                    if (kid != null && keyCache.containsKey(kid)) {
                        return keyCache.get(kid);
                    }
                    if (staticFallbackKey != null) {
                        return staticFallbackKey;
                    }
                    throw new IllegalArgumentException("Clé publique RSA introuvable pour kid=" + kid);
                })
                .onErrorResume(ex -> {
                    log.error("Échec de récupération JWKS depuis {} : {}", jwksUrl, ex.getMessage());
                    if (staticFallbackKey != null) {
                        log.info("Utilisation de la clé publique de secours statique");
                        return Mono.just(staticFallbackKey);
                    }
                    return Mono.error(ex);
                });
    }

    /**
     * Directly caches a public key (useful for tests and dynamic updates).
     */
    public void cacheKey(String kid, RSAPublicKey publicKey) {
        if (kid != null && publicKey != null) {
            keyCache.put(kid, publicKey);
        }
    }

    private Mono<Map<String, RSAPublicKey>> fetchJwks() {
        return webClient.get()
                .uri(jwksUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .map(this::parseJwksResponse);
    }

    private Map<String, RSAPublicKey> parseJwksResponse(String jwksJson) {
        try {
            JsonNode root = objectMapper.readTree(jwksJson);
            JsonNode keys = root.path("keys");

            if (keys.isArray()) {
                for (JsonNode keyNode : keys) {
                    String kty = keyNode.path("kty").asText();
                    String kid = keyNode.path("kid").asText();
                    String n = keyNode.path("n").asText();
                    String e = keyNode.path("e").asText();

                    if ("RSA".equalsIgnoreCase(kty) && !n.isBlank() && !e.isBlank()) {
                        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
                        KeyFactory kf = KeyFactory.getInstance("RSA");
                        RSAPublicKey rsaPublicKey = (RSAPublicKey) kf.generatePublic(spec);

                        keyCache.put(kid, rsaPublicKey);
                        log.info("Clé publique JWKS mise en cache avec succès [kid={}]", kid);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erreur lors du parsing du JWKS : {}", e.getMessage(), e);
        }
        return keyCache;
    }

    private RSAPublicKey parsePublicKey(String pem) throws Exception {
        String cleanPem = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(cleanPem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(encoded));
    }
}
