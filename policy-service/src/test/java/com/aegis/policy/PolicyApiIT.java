package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import com.aegis.policy.api.dto.BindPolicyRequest;
import com.aegis.policy.api.dto.CancelPolicyRequest;
import com.aegis.policy.api.dto.CoverageDto;
import com.aegis.policy.api.dto.CoverageVerificationResponse;
import com.aegis.policy.api.dto.EndorsePolicyRequest;
import com.aegis.policy.api.dto.PolicyResponse;
import com.aegis.policy.api.dto.PolicyVersionResponse;
import com.aegis.policy.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** The REST surface, exercised over real HTTP against real PostgreSQL. */
class PolicyApiIT extends PostgresIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    private static String uniquePolicyNumber() {
        // Tests in this class share one database and do not roll back, because they go over
        // HTTP and the server runs the transaction. Unique numbers keep them independent.
        return "AUTO-IT-%04d".formatted(SEQUENCE.incrementAndGet());
    }

    private static CoverageDto collision(String limit) {
        return new CoverageDto("COLLISION", Money.of(limit, "USD"), Money.of("500.00", "USD"), Set.of("RACING"));
    }

    private ResponseEntity<PolicyResponse> bind(String policyNumber, String limit) {
        return rest.postForEntity(
                "/api/v1/policies",
                new BindPolicyRequest(
                        policyNumber,
                        "PERSONAL_AUTO",
                        LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2027-01-01"),
                        "Priya Raman",
                        "TX-DALLAS",
                        List.of(collision(limit))),
                PolicyResponse.class);
    }

    private ResponseEntity<PolicyResponse> endorse(String policyNumber, String effectiveFrom, String limit) {
        return rest.postForEntity(
                "/api/v1/policies/{n}/endorsements",
                new EndorsePolicyRequest(
                        LocalDate.parse(effectiveFrom),
                        "Priya Raman",
                        "TX-DALLAS",
                        "Limit changed to " + limit,
                        List.of(collision(limit))),
                PolicyResponse.class,
                policyNumber);
    }

    private CoverageVerificationResponse verify(String policyNumber, String lossDate) {
        return rest.getForObject(
                "/api/v1/policies/{n}/coverage-verification?lossDate={d}&coverageCode=COLLISION",
                CoverageVerificationResponse.class,
                policyNumber,
                lossDate);
    }

    @Test
    @DisplayName("the headline story: a loss before an endorsement is settled on the ORIGINAL limit")
    void aLossBeforeAnEndorsementResolvesToTheOriginalLimit() {
        String policyNumber = uniquePolicyNumber();

        // 1 January: policy bound with a 50,000 collision limit.
        assertThat(bind(policyNumber, "50000.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Later: the limit is raised to 100,000, effective 1 June.
        assertThat(endorse(policyNumber, "2026-06-01", "100000.00").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A collision on 12 March is settled against the terms that applied on 12 March.
        CoverageVerificationResponse march = verify(policyNumber, "2026-03-12");
        assertThat(march.policyInForce()).isTrue();
        assertThat(march.coverageFound()).isTrue();
        assertThat(march.limit()).isEqualTo(Money.of("50000.00", "USD"));
        assertThat(march.deductible()).isEqualTo(Money.of("500.00", "USD"));
        assertThat(march.explanation()).contains("50000.00 USD");

        // A collision in July gets the raised limit. Same policy, same request, different date.
        assertThat(verify(policyNumber, "2026-07-12").limit()).isEqualTo(Money.of("100000.00", "USD"));
    }

    @Test
    @DisplayName("a backdated endorsement does not erase an endorsement already recorded for a later date")
    void backdatedEndorsementSlotsIntoTheTimeline() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");
        endorse(policyNumber, "2026-06-01", "100000.00");

        // Keyed in now, but effective from 1 April.
        endorse(policyNumber, "2026-04-01", "80000.00");

        assertThat(verify(policyNumber, "2026-03-31").limit()).isEqualTo(Money.of("50000.00", "USD"));
        assertThat(verify(policyNumber, "2026-04-15").limit()).isEqualTo(Money.of("80000.00", "USD"));
        assertThat(verify(policyNumber, "2026-06-15").limit()).isEqualTo(Money.of("100000.00", "USD"));
    }

    @Test
    void resolvesAVersionForAnExplicitAsOfDate() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");
        endorse(policyNumber, "2026-06-01", "100000.00");

        PolicyVersionResponse version =
                rest.getForObject("/api/v1/policies/{n}?asOf=2026-03-12", PolicyVersionResponse.class, policyNumber);

        assertThat(version.effectiveFrom()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(version.effectiveTo()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(version.supersededAt()).isNull();
        assertThat(version.coverages()).singleElement().satisfies(c -> {
            assertThat(c.coverageCode()).isEqualTo("COLLISION");
            assertThat(c.limit()).isEqualTo(Money.of("50000.00", "USD"));
            assertThat(c.exclusions()).containsExactly("RACING");
        });
    }

    @Test
    void historyExposesSupersededVersionsAsWellAsCurrentOnes() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");
        endorse(policyNumber, "2026-06-01", "100000.00");

        List<PolicyVersionResponse> history = rest.exchange(
                        "/api/v1/policies/{n}/versions",
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        new ParameterizedTypeReference<List<PolicyVersionResponse>>() {},
                        policyNumber)
                .getBody();

        assertThat(history).hasSize(3);
        assertThat(history).filteredOn(v -> v.supersededAt() != null).hasSize(1);
        assertThat(history).extracting(PolicyVersionResponse::changeType).contains("BOUND", "SPLIT", "ENDORSED");
    }

    @Test
    void cancellationTakesThePolicyOffRiskFromItsEffectiveDate() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        ResponseEntity<PolicyResponse> response = rest.postForEntity(
                "/api/v1/policies/{n}/cancellation",
                new CancelPolicyRequest(LocalDate.parse("2026-09-01"), "Non-payment of premium"),
                PolicyResponse.class,
                policyNumber);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(verify(policyNumber, "2026-08-31").policyInForce()).isTrue();

        CoverageVerificationResponse afterCancellation = verify(policyNumber, "2026-09-02");
        assertThat(afterCancellation.policyInForce()).isFalse();
        assertThat(afterCancellation.coverageFound()).isFalse();
        assertThat(afterCancellation.limit()).isNull();
        assertThat(afterCancellation.explanation()).contains("cancelled with effect from 2026-09-01");
    }

    @Test
    void coverageVerificationAnswers200WithNotCoveredRatherThanAnError() {
        // The distinction matters: claims must be able to tell "no cover" from "policy service
        // is down", because one denies a claim and the other must be retried.
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        ResponseEntity<CoverageVerificationResponse> beforeInception = rest.getForEntity(
                "/api/v1/policies/{n}/coverage-verification?lossDate=2025-06-01&coverageCode=COLLISION",
                CoverageVerificationResponse.class,
                policyNumber);
        assertThat(beforeInception.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beforeInception.getBody().policyInForce()).isFalse();
        assertThat(beforeInception.getBody().explanation()).contains("No version of policy");

        ResponseEntity<CoverageVerificationResponse> wrongCoverage = rest.getForEntity(
                "/api/v1/policies/{n}/coverage-verification?lossDate=2026-06-01&coverageCode=FLOOD",
                CoverageVerificationResponse.class,
                policyNumber);
        assertThat(wrongCoverage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wrongCoverage.getBody().policyInForce()).isTrue();
        assertThat(wrongCoverage.getBody().coverageFound()).isFalse();
        assertThat(wrongCoverage.getBody().explanation()).contains("carried no FLOOD coverage");
    }

    @Test
    void rejectsADuplicatePolicyNumberWith409AndAProblemType() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/policies",
                new BindPolicyRequest(
                        policyNumber,
                        "PERSONAL_AUTO",
                        LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2027-01-01"),
                        "Someone Else",
                        "TX-DALLAS",
                        List.of(collision("10000.00"))),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/duplicate-policy-number");
        assertThat(response.getBody().getProperties()).containsEntry("policyNumber", policyNumber);
    }

    @Test
    void rejectsAnEndorsementOutsideThePolicyPeriodWith422() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/policies/{n}/endorsements",
                new EndorsePolicyRequest(
                        LocalDate.parse("2028-01-01"),
                        "Priya Raman",
                        "TX-DALLAS",
                        "after expiry",
                        List.of(collision("10000.00"))),
                ProblemDetail.class,
                policyNumber);

        // 422, not 400: the request was perfectly well formed. A business rule rejected it.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/policy-not-in-force");
        assertThat(response.getBody().getProperties()).containsEntry("effectiveFrom", "2028-01-01");
    }

    @Test
    void rejectsAnInvalidRequestWith400AndNamesTheOffendingFields() {
        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/policies", new BindPolicyRequest("", "", null, null, "", "", List.of()), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/validation-failed");
        @SuppressWarnings("unchecked")
        var errors = (java.util.Map<String, String>)
                response.getBody().getProperties().get("errors");
        assertThat(errors).containsKeys("policyNumber", "productCode", "inceptionDate", "insuredName", "coverages");
    }

    @Test
    void returns404ForAnUnknownPolicy() {
        ResponseEntity<ProblemDetail> response =
                rest.getForEntity("/api/v1/policies/DOES-NOT-EXIST", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/policy-not-found");
    }

    @Test
    void returns404WhenThePolicyExistsButNoVersionWasInForceOnThatDate() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        ResponseEntity<ProblemDetail> response =
                rest.getForEntity("/api/v1/policies/{n}?asOf=2020-01-01", ProblemDetail.class, policyNumber);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/no-version-in-force");
        assertThat(response.getBody().getProperties()).containsEntry("asOf", "2020-01-01");
    }

    @Test
    void serialisesMoneyAsAStringPairRatherThanAFloatingPointNumber() {
        String policyNumber = uniquePolicyNumber();
        bind(policyNumber, "50000.00");

        String json = rest.getForObject(
                "/api/v1/policies/{n}/coverage-verification?lossDate=2026-06-01&coverageCode=COLLISION",
                String.class,
                policyNumber);

        assertThat(json).contains("\"limit\":{\"amount\":\"50000.00\",\"currency\":\"USD\"}");
        assertThat(json).doesNotContain("\"amount\":50000");
    }

    @Test
    void servesItsOwnOpenApiDocument() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getBody()).contains("Aegis Core - Policy Service").contains("/coverage-verification");
    }

    @Test
    void acceptsRequestsWithJsonContentTypeOnly() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response =
                rest.exchange("/api/v1/policies", HttpMethod.POST, new HttpEntity<>("not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
