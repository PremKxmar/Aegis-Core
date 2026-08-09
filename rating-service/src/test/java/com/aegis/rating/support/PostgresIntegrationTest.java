package com.aegis.rating.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests needing a real PostgreSQL, with the seeded rate tables that
 * {@code V2__seed_rate_tables.sql} publishes.
 *
 * <p>Real Postgres rather than H2, so the migrations — including the {@code quote_number_seq}
 * sequence and the check constraints on band ranges — are actually exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:17.10"))
            .withDatabaseName("aegis_rating")
            .withUsername("rating_svc")
            .withPassword("rating_svc");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
