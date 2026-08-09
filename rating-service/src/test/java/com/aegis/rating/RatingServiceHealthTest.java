package com.aegis.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.rating.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** See {@code PolicyServiceHealthTest} for why this asserts on probes rather than just startup. */
class RatingServiceHealthTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void reportsHealthyIncludingTheDatabase() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").contains("\"db\"");
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
        assertThat(response.getBody()).contains("\"artifact\":\"rating-service\"");
    }
}
