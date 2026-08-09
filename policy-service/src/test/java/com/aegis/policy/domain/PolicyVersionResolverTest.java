package com.aegis.policy.domain;

import static com.aegis.policy.domain.PolicyFixtures.collision;
import static com.aegis.policy.domain.PolicyFixtures.date;
import static com.aegis.policy.domain.PolicyFixtures.instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Boundary behaviour of the resolver itself, independent of how a policy got into that state. */
class PolicyVersionResolverTest {

    private static Policy policyWithOneVersion() {
        return Policy.bind(
                "AUTO-9999",
                "PERSONAL_AUTO",
                date("2026-01-01"),
                date("2027-01-01"),
                instant("2026-01-01"),
                "Priya Raman",
                "TX-DALLAS",
                List.of(collision("50000.00")));
    }

    @Test
    void returnsEmptyForAPolicyWithNoVersions() {
        assertThat(PolicyVersionResolver.resolve(List.of(), date("2026-06-01"), instant("2026-06-01")))
                .isEmpty();
    }

    @Test
    void treatsBothPeriodsAsHalfOpen() {
        PolicyVersion version = policyWithOneVersion().allVersions().getFirst();

        // valid time: [2026-01-01, 2027-01-01)
        assertThat(version.isValidOn(date("2025-12-31"))).isFalse();
        assertThat(version.isValidOn(date("2026-01-01"))).isTrue();
        assertThat(version.isValidOn(date("2026-12-31"))).isTrue();
        assertThat(version.isValidOn(date("2027-01-01"))).isFalse();

        // transaction time: [2026-01-01, ∞)
        assertThat(version.wasBelievedAt(instant("2025-12-31"))).isFalse();
        assertThat(version.wasBelievedAt(instant("2026-01-01"))).isTrue();
        assertThat(version.wasBelievedAt(instant("2030-01-01"))).isTrue();
    }

    @Test
    void aSupersededVersionIsBelievedUpToButNotIncludingTheInstantItWasSuperseded() {
        Policy policy = policyWithOneVersion();
        policy.endorse(
                date("2026-06-01"),
                instant("2026-05-01"),
                "Priya Raman",
                "TX-DALLAS",
                "raise limit",
                List.of(collision("70000.00")));

        PolicyVersion original = policy.allVersions().getFirst();

        assertThat(original.wasBelievedAt(instant("2026-04-30"))).isTrue();
        assertThat(original.wasBelievedAt(instant("2026-05-01"))).isFalse();
    }

    @Test
    void refusesToGuessWhenTwoVersionsOverlap() {
        // Two BOUND versions on one policy is not a state the write path can produce; this
        // constructs it directly to prove the resolver fails loudly rather than picking one.
        Policy policy = policyWithOneVersion();
        PolicyVersion first = policy.allVersions().getFirst();
        PolicyVersion overlapping = PolicyVersion.bind(
                policy,
                date("2026-03-01"),
                date("2026-09-01"),
                instant("2026-01-01"),
                "Priya Raman",
                "TX-DALLAS",
                List.of(collision("11111.00")));

        assertThatExceptionOfType(AmbiguousPolicyHistoryException.class)
                .isThrownBy(() -> PolicyVersionResolver.resolve(
                        List.of(first, overlapping), date("2026-06-01"), instant("2026-06-01")))
                .withMessageContaining("ambiguous")
                .withMessageContaining("2 versions");
    }

    @Test
    void rejectsNullArguments() {
        List<PolicyVersion> versions = policyWithOneVersion().allVersions();

        assertThatNullPointerException()
                .isThrownBy(() -> PolicyVersionResolver.resolve(null, date("2026-06-01"), instant("2026-06-01")));
        assertThatNullPointerException()
                .isThrownBy(() -> PolicyVersionResolver.resolve(versions, null, instant("2026-06-01")));
        assertThatNullPointerException()
                .isThrownBy(() -> PolicyVersionResolver.resolve(versions, date("2026-06-01"), null));
    }

    @Test
    void currentChainExcludesSupersededVersionsAndOrdersByEffectiveDate() {
        Policy policy = policyWithOneVersion();
        policy.endorse(
                date("2026-09-01"),
                instant("2026-05-01"),
                "Priya Raman",
                "TX-DALLAS",
                "later",
                List.of(collision("90000.00")));
        policy.endorse(
                date("2026-03-01"),
                instant("2026-06-01"),
                "Priya Raman",
                "TX-DALLAS",
                "backdated, higher version number but earlier date",
                List.of(collision("30000.00")));

        List<PolicyVersion> chain = PolicyVersionResolver.currentChain(policy.allVersions());

        assertThat(chain).allMatch(PolicyVersion::isCurrentBelief);
        assertThat(chain)
                .extracting(PolicyVersion::effectiveFrom)
                .containsExactly(date("2026-01-01"), date("2026-03-01"), date("2026-09-01"));
        // The last recorded version is not the last in the timeline - proof that resolution
        // must order by effective date, never by version number.
        assertThat(chain.getLast().versionNumber()).isLessThan(chain.get(1).versionNumber());
    }

    @Test
    void aVersionCannotBeSupersededTwice() {
        Policy policy = policyWithOneVersion();
        PolicyVersion version = policy.allVersions().getFirst();
        version.supersede(instant("2026-05-01"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> version.supersede(instant("2026-06-01")))
                .withMessageContaining("already superseded");
    }
}
