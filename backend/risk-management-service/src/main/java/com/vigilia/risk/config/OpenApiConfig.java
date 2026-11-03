package com.vigilia.risk.config;

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
 * Configuration OpenAPI 3 / Swagger UI avec support de l'authentification JWT Bearer pour Risk Management Service.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/health/live",
            "/actuator"
    );

    @Bean
    public OpenAPI riskOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vigilia - Risk Management Service API")
                        .description("Service d'évaluation des risques en temps réel, intégration avec le serving ML FastAPI, politiques déterministes et workflow de revue humaine (PENDING_REVIEW).")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Saisissez votre jeton JWT (RS256) avec rôle ANALYST ou ADMIN pour autoriser les opérations d'évaluation et de revue.")));
    }

    @Bean
    public OpenApiCustomizer riskSecurityOpenApiCustomizer() {
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
