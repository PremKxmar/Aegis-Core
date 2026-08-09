package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import com.aegis.policy.api.dto.BindPolicyRequest;
import com.aegis.policy.api.dto.CoverageDto;
import com.aegis.policy.api.dto.PolicyResponse;
import com.aegis.policy.support.PostgresIntegrationTest;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Validates real HTTP responses against the committed OpenAPI document.
 *
 * <p>{@code OpenApiSpecificationTest} proves the document matches the controllers' *declared*
 * shape. This proves the bytes actually on the wire conform to it — which is a different claim,
 * and the one a client generating code from the document is relying on. A field that serialises
 * as a number where the schema says string, or a required property that comes back null, fails
 * here and nowhere else.
 */
class OpenApiResponseConformanceIT extends PostgresIntegrationTest {

    private static final String POLICY_NUMBER = "AUTO-SPEC-001";

    private static OpenApiInteractionValidator validator;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void loadSpecification() {
        validator = OpenApiInteractionValidator.createForSpecificationUrl("openapi/policy-api.yaml")
                .build();
    }

    private void bindPolicyIfAbsent() {
        if (rest.getForEntity("/api/v1/policies/{n}/versions", String.class, POLICY_NUMBER)
                .getStatusCode()
                .is2xxSuccessful()) {
            return;
        }
        ResponseEntity<PolicyResponse> response = rest.postForEntity(
                "/api/v1/policies",
                new BindPolicyRequest(
                        POLICY_NUMBER,
                        "PERSONAL_AUTO",
                        LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2027-01-01"),
                        "Priya Raman",
                        "TX-DALLAS",
                        List.of(new CoverageDto(
                                "COLLISION",
                                Money.of("50000.00", "USD"),
                                Money.of("500.00", "USD"),
                                Set.of("RACING")))),
                PolicyResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void assertConforms(String specPath, Request.Method method, ResponseEntity<String> response) {
        ValidationReport report = validator.validateResponse(
                specPath,
                method,
                SimpleResponse.Builder.status(response.getStatusCode().value())
                        .withContentType("application/json")
                        .withBody(response.getBody())
                        .build());

        assertThat(report.hasErrors())
                .as("Response did not conform to the OpenAPI document: %s", report)
                .isFalse();
    }

    @Test
    void coverageVerificationResponseConformsToTheSpecification() {
        bindPolicyIfAbsent();

        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/policies/{n}/coverage-verification?lossDate=2026-03-12&coverageCode=COLLISION",
                String.class,
                POLICY_NUMBER);

        assertConforms("/api/v1/policies/{policyNumber}/coverage-verification", Request.Method.GET, response);
    }

    @Test
    void policyVersionResponseConformsToTheSpecification() {
        bindPolicyIfAbsent();

        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/policies/{n}?asOf=2026-06-01", String.class, POLICY_NUMBER);

        assertConforms("/api/v1/policies/{policyNumber}", Request.Method.GET, response);
    }

    @Test
    void historyResponseConformsToTheSpecification() {
        bindPolicyIfAbsent();

        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/policies/{n}/versions", String.class, POLICY_NUMBER);

        assertConforms("/api/v1/policies/{policyNumber}/versions", Request.Method.GET, response);
    }
}
