package org.ikigaidigital;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL in Docker for integration tests; Spring starts it and points the datasource at it.
 * Contexts with the same configuration are cached, so test classes with identical configuration
 * share one container (classes with other properties or bean overrides get their own).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same image as docker-compose.yml.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }
}
