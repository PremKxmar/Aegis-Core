package com.aegis.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import com.aegis.rating.api.dto.QuoteRequest;
import com.aegis.rating.api.dto.QuoteResponse;
import com.aegis.rating.api.dto.RateTableResponse;
import com.aegis.rating.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** The rating API over real HTTP, against the rate tables the Flyway seed publishes. */
class RatingApiIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static QuoteRequest autoRisk(String effectiveDate, String territory, int age, int claims, int policies) {
        return new QuoteRequest(
                "PERSONAL_AUTO",
                LocalDate.parse(effectiveDate),
                territory,
                age,
                claims,
                policies,
                List.of(new QuoteRequest.CoverageRequest("COLLISION", Money.of("50000.00", "USD"))));
    }

    private ResponseEntity<QuoteResponse> quote(QuoteRequest request) {
        return rest.postForEntity("/api/v1/quotes", request, QuoteResponse.class);
    }

    @Test
    void issuesAQuoteWithAFullWorksheet() {
        ResponseEntity<QuoteResponse> response = quote(autoRisk("2026-06-01", "TX-DALLAS", 5, 1, 2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuoteResponse quote = response.getBody();

        assertThat(quote.quoteNumber()).startsWith("QT-");
        assertThat(quote.rateTable()).isEqualTo("PERSONAL_AUTO v2");
        assertThat(quote.totalPremium().currencyCode()).isEqualTo("USD");

        assertThat(quote.worksheet())
                .hasSize(6)
                .extracting(QuoteResponse.WorksheetLineResponse::stepType)
                .containsExactly(
                        "BASE_RATE",
                        "TERRITORY_FACTOR",
                        "ASSET_AGE_FACTOR",
                        "CLAIMS_HISTORY_SURCHARGE",
                        "MULTI_POLICY_DISCOUNT",
                        "MINIMUM_PREMIUM_FLOOR");

        // The worksheet must reconcile to the number the customer is quoted.
        assertThat(quote.worksheet().getLast().subtotal()).isEqualTo(quote.totalPremium());
    }

    @Test
    @DisplayName("the effective date selects the rate table version, not today's date")
    void ratesAgainstTheTableInForceOnTheEffectiveDate() {
        // 2025 falls in PERSONAL_AUTO v1, 2026 in v2. Same risk, different filed rates.
        QuoteResponse under2025 =
                quote(autoRisk("2025-06-01", "TX-DALLAS", 5, 1, 2)).getBody();
        QuoteResponse under2026 =
                quote(autoRisk("2026-06-01", "TX-DALLAS", 5, 1, 2)).getBody();

        assertThat(under2025.rateTable()).isEqualTo("PERSONAL_AUTO v1");
        assertThat(under2026.rateTable()).isEqualTo("PERSONAL_AUTO v2");
        // v2 raised base rates and factors, so the same risk costs more.
        assertThat(under2026.totalPremium()).isGreaterThan(under2025.totalPremium());
    }

    @Test
    void ratingTheSameRiskTwiceProducesTheSamePremium() {
        QuoteRequest risk = autoRisk("2026-06-01", "TX-DALLAS", 7, 2, 3);

        QuoteResponse first = quote(risk).getBody();
        QuoteResponse second = quote(risk).getBody();

        assertThat(second.totalPremium()).isEqualTo(first.totalPremium());
        assertThat(second.worksheet()).usingRecursiveComparison().isEqualTo(first.worksheet());
        // Different quote references, identical arithmetic.
        assertThat(second.quoteNumber()).isNotEqualTo(first.quoteNumber());
    }

    @Test
    void storesTheWorksheetSoItCanBeRetrievedLater() {
        QuoteResponse issued =
                quote(autoRisk("2026-06-01", "IA-RURAL", 12, 0, 1)).getBody();

        QuoteResponse retrieved = rest.getForObject("/api/v1/quotes/{n}", QuoteResponse.class, issued.quoteNumber());

        assertThat(retrieved.totalPremium()).isEqualTo(issued.totalPremium());
        assertThat(retrieved.worksheet()).usingRecursiveComparison().isEqualTo(issued.worksheet());
        assertThat(retrieved.rateTableVersion()).isEqualTo(issued.rateTableVersion());
    }

    @Test
    void appliesTheMinimumPremiumFloorToATinyRisk() {
        QuoteResponse quote = quote(new QuoteRequest(
                        "PERSONAL_AUTO",
                        LocalDate.parse("2026-06-01"),
                        "IA-RURAL",
                        0,
                        0,
                        1,
                        List.of(new QuoteRequest.CoverageRequest("COLLISION", Money.of("1000.00", "USD")))))
                .getBody();

        // v2 minimum premium is 300.00 USD.
        assertThat(quote.totalPremium()).isEqualTo(Money.of("300.00", "USD"));
        assertThat(quote.worksheet().getLast().description()).contains("APPLIED");
    }

    @Test
    void ratesAPropertyRiskWithTheSameEngine() {
        QuoteResponse quote = quote(new QuoteRequest(
                        "HOMEOWNERS",
                        LocalDate.parse("2026-06-01"),
                        "TX-COASTAL",
                        35,
                        1,
                        2,
                        List.of(
                                new QuoteRequest.CoverageRequest("DWELLING", Money.of("400000.00", "USD")),
                                new QuoteRequest.CoverageRequest("PERSONAL_PROPERTY", Money.of("100000.00", "USD")))))
                .getBody();

        assertThat(quote.rateTable()).isEqualTo("HOMEOWNERS v1");
        assertThat(quote.coverages()).hasSize(2);
        assertThat(quote.worksheet()).hasSize(7); // two base-rate lines, then five rules
        assertThat(quote.worksheet().getLast().subtotal()).isEqualTo(quote.totalPremium());
    }

    @Test
    void refusesAnUnratedTerritoryWith422() {
        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/quotes", autoRisk("2026-06-01", "MARS-OLYMPUS", 5, 1, 1), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/unratable-risk");
        assertThat(response.getBody().getDetail()).contains("does not rate territory 'MARS-OLYMPUS'");
    }

    @Test
    void refusesADateWithNoRateTableInForce() {
        ResponseEntity<ProblemDetail> response =
                rest.postForEntity("/api/v1/quotes", autoRisk("2020-01-01", "TX-DALLAS", 5, 1, 1), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getDetail()).contains("No rate table is in force");
    }

    @Test
    void rejectsInvalidInputWith400() {
        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/quotes", new QuoteRequest("", null, "", -1, -1, 0, List.of()), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        var errors = (java.util.Map<String, String>)
                response.getBody().getProperties().get("errors");
        assertThat(errors).containsKeys("productCode", "effectiveDate", "territory", "coverages");
    }

    @Test
    void returns404ForAnUnknownQuote() {
        ResponseEntity<ProblemDetail> response = rest.getForEntity("/api/v1/quotes/QT-NOPE", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getType()).hasToString("https://aegis.example/problems/quote-not-found");
    }

    @Test
    void publishesTheRateTablesThemselves() {
        List<RateTableResponse> tables = rest.exchange(
                        "/api/v1/rate-tables?productCode=PERSONAL_AUTO",
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        new ParameterizedTypeReference<List<RateTableResponse>>() {})
                .getBody();

        assertThat(tables).hasSize(2);
        assertThat(tables).extracting(RateTableResponse::tableVersion).containsExactly(1, 2);

        RateTableResponse v1 = tables.getFirst();
        assertThat(v1.effectiveFrom()).isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(v1.effectiveTo()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(v1.minimumPremium()).isEqualTo(Money.of("250.00", "USD"));
        assertThat(v1.baseRates())
                .extracting(RateTableResponse.BaseRateResponse::coverageCode)
                .containsExactly("COLLISION", "COMPREHENSIVE", "LIABILITY");

        // The seeded bands must tile their domain: no gaps, no overlaps, last one open-ended.
        assertThat(v1.assetAgeBands()).hasSize(4);
        for (int i = 0; i < v1.assetAgeBands().size() - 1; i++) {
            assertThat(v1.assetAgeBands().get(i).max())
                    .isEqualTo(v1.assetAgeBands().get(i + 1).min());
        }
        assertThat(v1.assetAgeBands().getLast().max()).isNull();
    }

    @Test
    void reportsWhichTableWouldPriceARiskOnAGivenDate() {
        RateTableResponse onBoundary = rest.getForObject(
                "/api/v1/rate-tables/effective?productCode=PERSONAL_AUTO&effectiveDate=2026-01-01",
                RateTableResponse.class);
        RateTableResponse dayBefore = rest.getForObject(
                "/api/v1/rate-tables/effective?productCode=PERSONAL_AUTO&effectiveDate=2025-12-31",
                RateTableResponse.class);

        // Half-open: the new table's effective date belongs to the new table.
        assertThat(onBoundary.tableVersion()).isEqualTo(2);
        assertThat(dayBefore.tableVersion()).isEqualTo(1);
    }

    @Test
    void serialisesMoneyAsAStringPairAndFactorsAsNumbers() {
        QuoteResponse quote =
                quote(autoRisk("2026-06-01", "TX-DALLAS", 5, 1, 2)).getBody();
        String json = rest.getForObject("/api/v1/quotes/{n}", String.class, quote.quoteNumber());

        assertThat(json).contains("\"currency\":\"USD\"");
        assertThat(json).contains("\"totalPremium\":{\"amount\":\"");
        // Factor values stay numeric: they are dimensionless multipliers, not money.
        assertThat(quote.worksheet().get(1).factorValue()).isInstanceOf(BigDecimal.class);
    }
}
