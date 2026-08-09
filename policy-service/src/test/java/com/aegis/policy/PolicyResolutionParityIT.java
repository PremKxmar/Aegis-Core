package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import com.aegis.policy.domain.Coverage;
import com.aegis.policy.domain.Policy;
import com.aegis.policy.domain.PolicyVersion;
import com.aegis.policy.repository.PolicyRepository;
import com.aegis.policy.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The as-of rule is written twice — once in Java in {@code PolicyVersionResolver}, once in SQL in
 * {@code PolicyRepository.resolveVersions}. Two implementations of one rule is a liability unless
 * something proves they agree, so this walks a grid of valid-time and transaction-time
 * coordinates over a deliberately awkward history and asserts both answer identically at every
 * point.
 *
 * <p>Why keep both at all? The Java version can be unit-tested exhaustively without a database,
 * which is what makes the resolution table cheap enough to be thorough. The SQL version is what
 * keeps a coverage lookup an indexed single-row query instead of loading a policy's entire
 * history into memory to answer one question.
 */
class PolicyResolutionParityIT extends PostgresIntegrationTest {

    private static final String POLICY_NUMBER = "AUTO-PARITY-01";

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private TransactionTemplate transactions;

    private static Coverage collision(String limit) {
        return new Coverage("COLLISION", Money.of(limit, "USD"), Money.of("500.00", "USD"), Set.of());
    }

    /**
     * A history chosen to hit every awkward shape at once: a forward-dated endorsement, a
     * backdated one that lands inside an earlier version and must not erase the later one, and a
     * cancellation recorded after both.
     */
    @BeforeEach
    void buildAwkwardHistory() {
        if (policies.existsByPolicyNumber(POLICY_NUMBER)) {
            return;
        }
        transactions.executeWithoutResult(status -> policies.save(Policy.bind(
                POLICY_NUMBER,
                "PERSONAL_AUTO",
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-01-01"),
                Instant.parse("2026-01-01T00:00:00Z"),
                "Priya Raman",
                "TX-DALLAS",
                List.of(collision("50000.00")))));

        transactions.executeWithoutResult(status -> policies.findByPolicyNumberForUpdate(POLICY_NUMBER)
                .orElseThrow()
                .endorse(
                        LocalDate.parse("2026-06-01"),
                        Instant.parse("2026-05-01T00:00:00Z"),
                        "Priya Raman",
                        "TX-DALLAS",
                        "forward-dated",
                        List.of(collision("100000.00"))));

        transactions.executeWithoutResult(status -> policies.findByPolicyNumberForUpdate(POLICY_NUMBER)
                .orElseThrow()
                .endorse(
                        LocalDate.parse("2026-04-01"),
                        Instant.parse("2026-07-15T00:00:00Z"),
                        "Priya Raman",
                        "TX-DALLAS",
                        "backdated",
                        List.of(collision("80000.00"))));

        transactions.executeWithoutResult(status -> policies.findByPolicyNumberForUpdate(POLICY_NUMBER)
                .orElseThrow()
                .cancel(LocalDate.parse("2026-11-01"), Instant.parse("2026-10-01T00:00:00Z"), "Non-payment"));
    }

    @Test
    void javaAndSqlResolversAgreeAtEveryPointOfTheGrid() {
        List<LocalDate> validDates = List.of(
                LocalDate.parse("2025-12-31"), // before inception
                LocalDate.parse("2026-01-01"), // inception, inclusive
                LocalDate.parse("2026-03-31"),
                LocalDate.parse("2026-04-01"), // backdated endorsement takes effect
                LocalDate.parse("2026-05-31"),
                LocalDate.parse("2026-06-01"), // forward-dated endorsement takes effect
                LocalDate.parse("2026-10-31"),
                LocalDate.parse("2026-11-01"), // cancellation takes effect
                LocalDate.parse("2026-12-31"),
                LocalDate.parse("2027-01-01")); // expiry, exclusive

        List<Instant> beliefInstants = List.of(
                Instant.parse("2025-12-31T00:00:00Z"), // before the policy was keyed in
                Instant.parse("2026-01-01T00:00:00Z"), // just bound
                Instant.parse("2026-05-01T00:00:00Z"), // forward-dated endorsement recorded
                Instant.parse("2026-07-15T00:00:00Z"), // backdated endorsement recorded
                Instant.parse("2026-10-01T00:00:00Z"), // cancellation recorded
                Instant.parse("2027-06-01T00:00:00Z")); // long after

        int compared = 0;
        for (LocalDate asOf : validDates) {
            for (Instant asAt : beliefInstants) {
                Optional<UUID> viaJava =
                        transactions.execute(status -> policies.findByPolicyNumberWithHistory(POLICY_NUMBER)
                                .orElseThrow()
                                .versionAsOf(asOf, asAt)
                                .map(PolicyVersion::id));

                List<PolicyVersion> viaSql =
                        transactions.execute(status -> policies.resolveVersions(POLICY_NUMBER, asOf, asAt));

                assertThat(viaSql)
                        .as(
                                "SQL returned more than one version for asOf=%s asAt=%s, which means the "
                                        + "write path produced overlapping periods",
                                asOf, asAt)
                        .hasSizeLessThanOrEqualTo(1);

                Optional<UUID> viaSqlId = viaSql.stream().map(PolicyVersion::id).findFirst();

                assertThat(viaSqlId)
                        .as("Java and SQL resolvers disagree at asOf=%s asAt=%s", asOf, asAt)
                        .isEqualTo(viaJava);
                compared++;
            }
        }

        assertThat(compared)
                .isEqualTo(validDates.size() * beliefInstants.size())
                .isEqualTo(60);
    }

    @Test
    void theSqlResolverReturnsTheCoverageTermsAndNotJustTheVersionRow() {
        List<PolicyVersion> versions = transactions.execute(status -> {
            List<PolicyVersion> found = policies.resolveVersions(
                    POLICY_NUMBER, LocalDate.parse("2026-04-15"), Instant.parse("2026-08-01T00:00:00Z"));
            // Touch the coverages inside the transaction: the query fetch-joins them, and this
            // asserts the join actually populated them rather than leaving a lazy proxy.
            found.forEach(PolicyVersion::coverages);
            return found;
        });

        assertThat(versions)
                .singleElement()
                .satisfies(version -> assertThat(version.coverage("COLLISION"))
                        .isPresent()
                        .get()
                        .extracting(Coverage::limit)
                        .isEqualTo(Money.of("80000.00", "USD")));
    }
}
