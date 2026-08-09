package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import com.aegis.policy.domain.ChangeType;
import com.aegis.policy.domain.Coverage;
import com.aegis.policy.domain.Policy;
import com.aegis.policy.domain.PolicyVersion;
import com.aegis.policy.repository.PolicyRepository;
import com.aegis.policy.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the mapping actually survives a round trip through PostgreSQL — the part that unit tests
 * over in-memory objects cannot tell you anything about.
 */
class PolicyPersistenceIT extends PostgresIntegrationTest {

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private static Coverage collision(String limit) {
        return new Coverage(
                "COLLISION", Money.of(limit, "USD"), Money.of("500.00", "USD"), Set.of("RACING", "COMMERCIAL_USE"));
    }

    private Policy bindPolicy(String number) {
        return policies.save(Policy.bind(
                number,
                "PERSONAL_AUTO",
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-01-01"),
                Instant.parse("2026-01-01T00:00:00Z"),
                "Priya Raman",
                "TX-DALLAS",
                List.of(collision("50000.00"))));
    }

    @Test
    @Transactional
    void moneyRoundTripsThroughAnEmbeddableWithoutLosingItsCurrencyOrScale() {
        Policy saved = bindPolicy("AUTO-P001");

        Policy reloaded = policies.findByPolicyNumber("AUTO-P001").orElseThrow();
        Coverage coverage =
                reloaded.currentChain().getFirst().coverage("COLLISION").orElseThrow();

        // The column is NUMERIC(19,4) but the value is USD, so it must come back at scale 2.
        assertThat(coverage.limit()).isEqualTo(Money.of("50000.00", "USD"));
        assertThat(coverage.limit().amount()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(coverage.deductible()).isEqualTo(Money.of("500.00", "USD"));
        assertThat(coverage.exclusions()).containsExactlyInAnyOrder("RACING", "COMMERCIAL_USE");
        assertThat(saved.id()).isEqualTo(reloaded.id());
    }

    @Test
    void endorsementWritesNewRowsAndNeverEditsTheTermsOfAnOldOne() {
        transactions.executeWithoutResult(status -> bindPolicy("AUTO-P002"));

        Object[] originalRow = jdbc.queryForObject(
                "select effective_from, effective_to, superseded_at from policy_version pv "
                        + "join policy p on p.id = pv.policy_id where p.policy_number = 'AUTO-P002'",
                (rs, i) -> new Object[] {rs.getDate(1), rs.getDate(2), rs.getTimestamp(3)});

        transactions.executeWithoutResult(status -> {
            Policy policy = policies.findByPolicyNumberForUpdate("AUTO-P002").orElseThrow();
            policy.endorse(
                    LocalDate.parse("2026-06-01"),
                    Instant.parse("2026-05-01T00:00:00Z"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "Raise limit",
                    List.of(collision("100000.00")));
        });

        // The original row's valid period is byte-for-byte what it was; only superseded_at moved.
        Object[] afterRow = jdbc.queryForObject(
                "select effective_from, effective_to, superseded_at from policy_version pv "
                        + "join policy p on p.id = pv.policy_id "
                        + "where p.policy_number = 'AUTO-P002' and pv.change_type = 'BOUND'",
                (rs, i) -> new Object[] {rs.getDate(1), rs.getDate(2), rs.getTimestamp(3)});

        assertThat(afterRow[0]).isEqualTo(originalRow[0]);
        assertThat(afterRow[1]).isEqualTo(originalRow[1]);
        assertThat(originalRow[2]).isNull();
        assertThat(afterRow[2]).isNotNull();

        Integer rowCount = jdbc.queryForObject(
                "select count(*) from policy_version pv join policy p on p.id = pv.policy_id "
                        + "where p.policy_number = 'AUTO-P002'",
                Integer.class);
        assertThat(rowCount).isEqualTo(3);
    }

    @Test
    void optimisticLockVersionIncrementsEvenThoughOnlyChildRowsWereAdded() {
        transactions.executeWithoutResult(status -> bindPolicy("AUTO-P003"));
        long before = transactions.execute(
                status -> policies.findByPolicyNumber("AUTO-P003").orElseThrow().lockVersion());

        transactions.executeWithoutResult(status -> policies.findByPolicyNumberForUpdate("AUTO-P003")
                .orElseThrow()
                .endorse(
                        LocalDate.parse("2026-06-01"),
                        Instant.parse("2026-05-01T00:00:00Z"),
                        "Priya Raman",
                        "TX-DALLAS",
                        "Raise limit",
                        List.of(collision("100000.00"))));

        long after = transactions.execute(
                status -> policies.findByPolicyNumber("AUTO-P003").orElseThrow().lockVersion());

        // Without OPTIMISTIC_FORCE_INCREMENT this stays put, because appending to a @OneToMany
        // does not make the parent row dirty - and the lock silently protects nothing.
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void databaseRejectsTwoCoverageLinesWithTheSameCodeOnOneVersion() {
        transactions.executeWithoutResult(status -> bindPolicy("AUTO-P004"));

        String versionId = jdbc.queryForObject(
                "select pv.id::text from policy_version pv join policy p on p.id = pv.policy_id "
                        + "where p.policy_number = 'AUTO-P004'",
                String.class);

        // The unique constraint is the last line of defence: two COLLISION lines on one version
        // would make "the collision limit" ambiguous, and the ambiguity would surface as an
        // arbitrary claim payment rather than as an error.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update("""
                        insert into coverage (id, policy_version_id, coverage_code, limit_amount,
                                              limit_currency, deductible_amount, deductible_currency)
                        values (gen_random_uuid(), ?::uuid, 'COLLISION', 1, 'USD', 0, 'USD')
                        """, versionId)))
                .isNotNull()
                .hasMessageContaining("coverage_unique_per_version");
    }

    @Test
    void flywayCreatedTheSchemaAndHibernateValidatedAgainstIt() {
        // Reaching this assertion at all means ddl-auto=validate passed at context startup:
        // every entity mapping matches a column Flyway created.
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
                String.class);

        assertThat(tables)
                .contains("policy", "policy_version", "coverage", "coverage_exclusion", "flyway_schema_history");
    }

    @Test
    void allVersionsIncludingSupersededOnesRemainReadable() {
        transactions.executeWithoutResult(status -> bindPolicy("AUTO-P005"));
        transactions.executeWithoutResult(status -> policies.findByPolicyNumberForUpdate("AUTO-P005")
                .orElseThrow()
                .endorse(
                        LocalDate.parse("2026-06-01"),
                        Instant.parse("2026-05-01T00:00:00Z"),
                        "Priya Raman",
                        "TX-DALLAS",
                        "Raise limit",
                        List.of(collision("100000.00"))));

        transactions.executeWithoutResult(status -> {
            Policy policy = policies.findByPolicyNumber("AUTO-P005").orElseThrow();

            assertThat(policy.allVersions()).hasSize(3);
            assertThat(policy.currentChain()).hasSize(2);
            assertThat(policy.allVersions())
                    .filteredOn(v -> !v.isCurrentBelief())
                    .singleElement()
                    .extracting(PolicyVersion::changeType)
                    .isEqualTo(ChangeType.BOUND);
        });
    }
}
