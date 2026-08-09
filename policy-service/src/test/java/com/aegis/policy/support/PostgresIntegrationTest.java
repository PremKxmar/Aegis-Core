package com.aegis.policy.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real PostgreSQL.
 *
 * <p>Real Postgres, not H2. H2's PostgreSQL compatibility mode does not implement partial
 * indexes, {@code TIMESTAMPTZ} semantics or {@code NUMERIC} the same way, and this service
 * depends on all three — so a green H2 suite would prove nothing about the thing being shipped,
 * and the Flyway migrations would not even be exercised.
 *
 * <p>The container is a singleton started once per JVM rather than a {@code @Container} field
 * started per class. Starting Postgres takes a couple of seconds; doing it for every test class
 * is how integration suites become slow enough that people stop running them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:17.10"))
            .withDatabaseName("aegis_policy")
            .withUsername("policy_svc")
            .withPassword("policy_svc");

    static {
        POSTGRES.start();
        // No explicit stop: Testcontainers' Ryuk sidecar reaps the container when the JVM exits,
        // including when the JVM is killed, which a shutdown hook would not survive.
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
