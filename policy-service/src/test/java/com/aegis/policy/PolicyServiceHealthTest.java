package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.policy.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Starts the real application against a real PostgreSQL and checks the endpoints Kubernetes
 * depends on. Deliberately not an empty "context loads" test: a context that starts but reports
 * no readiness probe still fails to deploy, and that should be caught here rather than during a
 * rollout.
 */
class PolicyServiceHealthTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void reportsHealthyIncludingTheDatabase() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        // The db health contributor proves the connection pool actually reached PostgreSQL,
        // rather than the context merely starting with a lazily-initialised DataSource.
        assertThat(response.getBody()).contains("\"db\"");
    }

    @Test
    void exposesSeparateLivenessAndReadinessProbes() {
        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class))
                .satisfies(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).contains("\"status\":\"UP\"");
                });
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class))
                .satisfies(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).contains("\"status\":\"UP\"");
                });
    }

    @Test
    void reportsBuildInformation() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"artifact\":\"policy-service\"");
    }
}
