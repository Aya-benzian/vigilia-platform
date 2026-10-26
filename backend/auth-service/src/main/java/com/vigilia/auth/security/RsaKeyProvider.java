package com.vigilia.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Manages the RSA 2048-bit cryptographic key pair for RS256 JWT signing and JWKS exposition.
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);
    private static final String DEFAULT_KID = "vigilia-key-1";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public RsaKeyProvider(
            @Value("${JWT_PRIVATE_KEY:}") String privateKeyPem,
            @Value("${JWT_PUBLIC_KEY:}") String publicKeyPem,
            @Value("${jwt.kid:vigilia-key-1}") String kid) {

        this.keyId = (kid != null && !kid.isBlank()) ? kid : DEFAULT_KID;

        RSAPrivateKey priv = null;
        RSAPublicKey pub = null;

        if (privateKeyPem != null && !privateKeyPem.isBlank() && publicKeyPem != null && !publicKeyPem.isBlank()) {
            try {
                priv = parsePrivateKey(privateKeyPem);
                pub = parsePublicKey(publicKeyPem);
                log.info("Clés RSA chargées avec succès depuis l'environnement [kid={}]", this.keyId);
            } catch (Exception e) {
                log.error("Échec du chargement des clés RSA fournies, génération d'une paire dynamique : {}", e.getMessage());
            }
        }

        if (priv == null || pub == null) {
            log.info("Aucune clé RSA externe valide configurée. Génération dynamique d'une paire RSA 2048-bit [kid={}]", this.keyId);
            KeyPair kp = generateKeyPair();
            priv = (RSAPrivateKey) kp.getPrivate();
            pub = (RSAPublicKey) kp.getPublic();
        }

        this.privateKey = priv;
        this.publicKey = pub;
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public String getKeyId() {
        return keyId;
    }

    /**
     * Produces standard RFC 7517 / RFC 7518 JWKS JSON representation.
     */
    public Map<String, Object> getJwks() {
        Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", keyId,
                "n", toBase64UrlUInt(publicKey.getModulus()),
                "e", toBase64UrlUInt(publicKey.getPublicExponent())
        );

        return Map.of("keys", List.of(jwk));
    }

    private String toBase64UrlUInt(BigInteger bi) {
        byte[] bytes = bi.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme RSA non supporté par la JVM", e);
        }
    }

    private RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        String cleanPem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(cleanPem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
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
