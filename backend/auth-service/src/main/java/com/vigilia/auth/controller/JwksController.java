package com.vigilia.auth.controller;

import com.vigilia.auth.security.RsaKeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "JWKS", description = "Publication des clés publiques pour la vérification des jetons JWT RS256")
public class JwksController {

    private final RsaKeyProvider rsaKeyProvider;

    public JwksController(RsaKeyProvider rsaKeyProvider) {
        this.rsaKeyProvider = rsaKeyProvider;
    }

    @GetMapping(value = {"/.well-known/jwks.json", "/api/v1/auth/.well-known/jwks.json"}, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON Web Key Set (JWKS)", description = "Expose les clés publiques RSA pour validation JWT par l'API Gateway et les microservices")
    public ResponseEntity<Map<String, Object>> getJwks() {
        return ResponseEntity.ok(rsaKeyProvider.getJwks());
    }
}
