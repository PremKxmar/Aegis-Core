package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Starts the real application context on a real port and checks the endpoints Kubernetes will
 * depend on. This is deliberately not an empty "context loads" test: a context that starts but
 * exposes no readiness probe still fails to deploy, and that failure should be caught here
 * rather than in a rollout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyServiceHealthTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void reportsHealthy() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
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
        // Proves the Gradle buildInfo() wiring produced META-INF/build-info.properties, so a
        // running pod can be traced back to the artefact version that produced it.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"artifact\":\"policy-service\"");
    }
}
