package com.vigilia.gateway.config;

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
 * Configuration OpenAPI 3 / Swagger UI pour l'API Gateway réactive (Spring WebFlux).
 * Supporte le schéma d'authentification JWT Bearer (RS256).
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/fallback",
            "/health/live",
            "/actuator"
    );

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vigilia - API Gateway")
                        .description("Passerelle réactive d'entrée (Spring Cloud Gateway WebFlux), validation des jetons JWT RS256, contrôle RBAC et routage résilient.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Saisissez votre jeton JWT (RS256) sans le préfixe 'Bearer ' pour autoriser les requêtes routées via la passerelle.")));
    }

    @Bean
    public OpenApiCustomizer gatewaySecurityOpenApiCustomizer() {
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
