package com.vigilia.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Configuration OpenAPI 3 / Swagger UI avec support de l'authentification JWT Bearer (RS256).
 * Distingue rigoureusement les endpoints publics des endpoints protégés.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/.well-known/jwks.json",
            "/api/v1/auth/.well-known/jwks.json",
            "/health/live",
            "/actuator"
    );

    @Bean
    public OpenAPI authOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vigilia - Authentication & Security Service API")
                        .description("Service d'authentification, de gestion des identités (rôles RBAC), d'émission des jetons JWT RS256 et d'exposition JWKS.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Saisissez votre jeton JWT (RS256) sans le préfixe 'Bearer ' pour accéder aux endpoints protégés.")));
    }

    /**
     * Applique automatiquement le schéma de sécurité Bearer JWT uniquement aux opérations protégées.
     * Les endpoints publics d'inscription, de connexion et de JWKS restent accessibles sans cadenas.
     */
    @Bean
    public OpenApiCustomizer authSecurityOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                boolean isPublic = PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
                if (!isPublic) {
                    pathItem.readOperations().forEach(operation ->
                            operation.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                    );
                }
            });
        };
    }
}
