package com.vigilia.transaction.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all Testcontainers-backed integration tests.
 * Uses the singleton container pattern (static initializer without @Container)
 * so the postgres:15-alpine container stays running across all test classes
 * sharing the Spring ApplicationContext in the same JVM.
 */
public abstract class PostgresIntegrationBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("vigilia_test")
            .withUsername("vigilia_test")
            .withPassword("vigilia_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driverClassName", () -> "org.postgresql.Driver");
    }
}
