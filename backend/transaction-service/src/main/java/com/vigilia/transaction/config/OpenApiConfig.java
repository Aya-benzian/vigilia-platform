package com.vigilia.transaction.config;

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
 * Configuration OpenAPI 3 / Swagger UI avec support de l'authentification JWT Bearer pour Transaction Service.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/health/live",
            "/actuator"
    );

    @Bean
    public OpenAPI transactionOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vigilia - Transaction Service API")
                        .description("Service de traitement des transactions financières (PURCHASE et TRANSFER), gestion des portefeuilles, réservations de fonds et journal comptable double-entry.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Saisissez votre jeton JWT (RS256) sans le préfixe 'Bearer ' pour autoriser les opérations financières.")));
    }

    @Bean
    public OpenApiCustomizer transactionSecurityOpenApiCustomizer() {
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
