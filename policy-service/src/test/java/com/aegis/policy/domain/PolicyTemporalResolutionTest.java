package com.aegis.policy.domain;

import static com.aegis.policy.domain.PolicyFixtures.COLLISION;
import static com.aegis.policy.domain.PolicyFixtures.annualAutoPolicy;
import static com.aegis.policy.domain.PolicyFixtures.collision;
import static com.aegis.policy.domain.PolicyFixtures.collisionLimitOn;
import static com.aegis.policy.domain.PolicyFixtures.date;
import static com.aegis.policy.domain.PolicyFixtures.instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The resolution table.
 *
 * <p>Each case below is a scenario an insurer actually hits, written out with the dates that
 * make it interesting. The base policy throughout is a one-year personal auto policy:
 *
 * <pre>
 *   AUTO-0001   valid 2026-01-01 .. 2027-01-01 (exclusive)
 *               collision limit 50,000  deductible 500
 *               bound (recorded) 2026-01-01
 * </pre>
 *
 * <p>Two dates matter in every case and they are not the same date:
 *
 * <ul>
 *   <li><b>asOf</b> — valid time. For a claim, the date of the loss.
 *   <li><b>asAt</b> — transaction time. When the question is being asked, or when it <i>was</i>
 *       asked if the point is to reconstruct a past decision.
 * </ul>
 */
class PolicyTemporalResolutionTest {

    @Nested
    @DisplayName("a policy with a single version")
    class SingleVersion {

        /*
         * | asOf         | expected                                  |
         * |--------------|-------------------------------------------|
         * | 2025-12-31   | none  - the day before inception          |
         * | 2026-01-01   | v1    - inception is INCLUSIVE            |
         * | 2026-06-15   | v1    - mid-term                          |
         * | 2026-12-31   | v1    - last covered day                  |
         * | 2027-01-01   | none  - expiry is EXCLUSIVE               |
         */
        @ParameterizedTest(name = "loss on {0} -> {1}")
        @CsvSource({
            "2025-12-31, none",
            "2026-01-01, 50000.00",
            "2026-06-15, 50000.00",
            "2026-12-31, 50000.00",
            "2027-01-01, none",
        })
        void resolvesOnlyWithinItsHalfOpenValidPeriod(String lossDate, String expected) {
            Policy policy = annualAutoPolicy("50000.00");

            String actual = collisionLimitOn(policy, lossDate, "2026-06-01");

            assertThat(actual).isEqualTo("none".equals(expected) ? null : expected);
        }

        @Test
        void isNotVisibleBeforeItWasRecorded() {
            // Asking what we knew before the policy was even keyed in must return nothing,
            // not the policy. This is what makes an audit of a past decision meaningful.
            Policy policy = annualAutoPolicy("50000.00");

            assertThat(policy.versionAsOf(date("2026-06-15"), instant("2025-12-31")))
                    .isEmpty();
            assertThat(policy.versionAsOf(date("2026-06-15"), instant("2026-01-01")))
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("a forward-dated endorsement")
    class ForwardDatedEndorsement {

        /*
         * Recorded 2026-05-01, effective 2026-06-01: the limit goes 50,000 -> 100,000.
         *
         * | asOf         | asAt         | expected  | why                                      |
         * |--------------|--------------|-----------|------------------------------------------|
         * | 2026-05-31   | now          | 50,000    | day before the endorsement takes effect  |
         * | 2026-06-01   | now          | 100,000   | effective date is inclusive              |
         * | 2026-06-15   | 2026-04-01   | 50,000    | before we had recorded the endorsement   |
         */
        private Policy endorsedPolicy() {
            Policy policy = annualAutoPolicy("50000.00");
            policy.endorse(
                    date("2026-06-01"),
                    instant("2026-05-01"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "Increased collision limit to 100,000",
                    List.of(collision("100000.00")));
            return policy;
        }

        @ParameterizedTest(name = "loss {0} asked at {1} -> {2}")
        @CsvSource({
            "2026-05-31, 2026-12-01, 50000.00",
            "2026-06-01, 2026-12-01, 100000.00",
            "2026-06-15, 2026-12-01, 100000.00",
            "2026-06-15, 2026-04-01, 50000.00",
        })
        void appliesFromItsEffectiveDateOnly(String lossDate, String askedAt, String expected) {
            assertThat(collisionLimitOn(endorsedPolicy(), lossDate, askedAt)).isEqualTo(expected);
        }

        @Test
        void splitsTheOriginalVersionRatherThanEditingIt() {
            Policy policy = endorsedPolicy();

            // The original v1 row still exists, unchanged, and still says 2026-01-01..2027-01-01.
            PolicyVersion original = policy.allVersions().stream()
                    .filter(v -> v.changeType() == ChangeType.BOUND)
                    .findFirst()
                    .orElseThrow();
            assertThat(original.effectiveFrom()).isEqualTo(date("2026-01-01"));
            assertThat(original.effectiveTo()).isEqualTo(date("2027-01-01"));
            assertThat(original.isCurrentBelief()).isFalse();
            assertThat(original.supersededAt()).isEqualTo(instant("2026-05-01"));

            // What we believe now is a two-link chain that covers the same span with no gap.
            assertThat(policy.currentChain())
                    .extracting(PolicyVersion::effectiveFrom, PolicyVersion::effectiveTo)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(date("2026-01-01"), date("2026-06-01")),
                            org.assertj.core.groups.Tuple.tuple(date("2026-06-01"), date("2027-01-01")));
        }

        @Test
        void inheritsTheExpiryOfTheVersionItDisplaces() {
            // An endorsement changes the terms, not the policy period. If the new version ran to
            // infinity it would silently extend cover past expiry - free insurance, and a real
            // production failure mode.
            PolicyVersion latest = endorsedPolicy().currentChain().getLast();

            assertThat(latest.effectiveTo()).isEqualTo(date("2027-01-01"));
        }
    }

    @Nested
    @DisplayName("a same-day endorsement")
    class SameDayEndorsement {

        @Test
        void replacesTheVersionEntirelyWithoutLeavingAZeroLengthFragment() {
            // Effective on the very first day the displaced version was valid. There is no
            // surviving fragment before it, so no split row should be written - a
            // [2026-01-01, 2026-01-01) period covers nothing and would be a latent trap.
            Policy policy = annualAutoPolicy("50000.00");

            policy.endorse(
                    date("2026-01-01"),
                    instant("2026-01-01"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "Corrected limit on day one",
                    List.of(collision("75000.00")));

            assertThat(policy.currentChain()).singleElement().satisfies(v -> {
                assertThat(v.effectiveFrom()).isEqualTo(date("2026-01-01"));
                assertThat(v.effectiveTo()).isEqualTo(date("2027-01-01"));
            });
            assertThat(collisionLimitOn(policy, "2026-01-01", "2026-02-01")).isEqualTo("75000.00");
            assertThat(policy.allVersions()).noneMatch(v -> v.changeType() == ChangeType.SPLIT);
        }
    }

    @Nested
    @DisplayName("a backdated endorsement")
    class BackdatedEndorsement {

        /*
         * The case the whole system exists for.
         *
         *   2026-01-01  bound,             limit  50,000, effective from 2026-01-01
         *   2026-05-01  endorsement A,     limit 100,000, effective from 2026-06-01
         *   2026-07-15  endorsement B,     limit  80,000, effective from 2026-04-01  (BACKDATED)
         *
         * After B, the timeline the system believes is:
         *
         *   2026-01-01 .. 2026-04-01   50,000
         *   2026-04-01 .. 2026-06-01   80,000   <- B slots in here
         *   2026-06-01 .. 2027-01-01  100,000   <- A survives, it is not erased
         */
        private Policy backdated() {
            Policy policy = annualAutoPolicy("50000.00");
            policy.endorse(
                    date("2026-06-01"),
                    instant("2026-05-01"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "A: raise to 100,000 from June",
                    List.of(collision("100000.00")));
            policy.endorse(
                    date("2026-04-01"),
                    instant("2026-07-15"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "B: backdated raise to 80,000 from April",
                    List.of(collision("80000.00")));
            return policy;
        }

        @ParameterizedTest(name = "loss on {0} -> {1}")
        @CsvSource({
            "2026-03-31,  50000.00",
            "2026-04-01,  80000.00",
            "2026-05-31,  80000.00",
            "2026-06-01, 100000.00",
            "2026-12-31, 100000.00",
        })
        void slotsIntoTheTimelineWithoutErasingLaterEndorsements(String lossDate, String expected) {
            assertThat(collisionLimitOn(backdated(), lossDate, "2026-08-01")).isEqualTo(expected);
        }

        @Test
        void leavesTheBelievedTimelineContiguousAndGapFree() {
            assertContiguousChain(backdated());
        }

        @Test
        void answersWhatWeBelievedBeforeTheBackdatedEndorsementWasRecorded() {
            Policy policy = backdated();

            // A claim for a 15 April loss, assessed on 1 June: the backdated endorsement had not
            // been keyed in yet, so the honest answer for that decision is 50,000.
            assertThat(collisionLimitOn(policy, "2026-04-15", "2026-06-01")).isEqualTo("50000.00");

            // The same loss, reassessed today, is covered at 80,000. Both answers are correct;
            // they answer different questions. Only a bitemporal model can produce both.
            assertThat(collisionLimitOn(policy, "2026-04-15", "2026-08-01")).isEqualTo("80000.00");
        }

        @Test
        void keepsEveryVersionEverRecorded() {
            Policy policy = backdated();

            // v1 bound, A's split of v1, A itself, B's split of A's split, B itself.
            assertThat(policy.allVersions()).hasSize(5);
            assertThat(policy.currentChain()).hasSize(3);
            assertThat(policy.allVersions())
                    .filteredOn(v -> !v.isCurrentBelief())
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        private Policy cancelled() {
            Policy policy = annualAutoPolicy("50000.00");
            policy.cancel(date("2026-09-01"), instant("2026-08-20"), "Non-payment of premium");
            return policy;
        }

        @ParameterizedTest(name = "loss on {0} -> covered={1}")
        @CsvSource({
            "2026-08-31, true",
            "2026-09-01, false",
            "2026-12-31, false",
        })
        void endsCoverageFromItsEffectiveDate(String lossDate, boolean covered) {
            Policy policy = cancelled();

            PolicyVersion version = policy.versionAsOf(date(lossDate), instant("2026-10-01"))
                    .orElseThrow(() -> new AssertionError("expected a version for " + lossDate));

            assertThat(version.isInForce()).isEqualTo(covered);
        }

        @Test
        void resolvesToACancelledVersionRatherThanToNothing() {
            // "Cancelled" and "never existed" must not look the same to a claim handler.
            PolicyVersion version = cancelled()
                    .versionAsOf(date("2026-10-01"), instant("2026-10-01"))
                    .orElseThrow();

            assertThat(version.status()).isEqualTo(PolicyStatus.CANCELLED);
            assertThat(version.changeType()).isEqualTo(ChangeType.CANCELLED);
            assertThat(version.coverages()).isEmpty();
            assertThat(version.coverage(COLLISION)).isEmpty();
        }

        @Test
        void aBackdatedCancellationRemovesLaterEndorsementsFromTheCurrentTimeline() {
            Policy policy = annualAutoPolicy("50000.00");
            policy.endorse(
                    date("2026-06-01"),
                    instant("2026-05-01"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "Raise to 100,000",
                    List.of(collision("100000.00")));

            // Cancelled effective 1 March, recorded in October - after the June endorsement.
            policy.cancel(date("2026-03-01"), instant("2026-10-01"), "Rescinded: material misstatement");

            assertThat(collisionLimitOn(policy, "2026-02-28", "2026-11-01")).isEqualTo("50000.00");
            assertThat(collisionLimitOn(policy, "2026-06-15", "2026-11-01")).isNull();
            assertThat(policy.versionAsOf(date("2026-06-15"), instant("2026-11-01"))
                            .orElseThrow()
                            .status())
                    .isEqualTo(PolicyStatus.CANCELLED);

            // But the June endorsement is still on record, and still visible as of September.
            assertThat(collisionLimitOn(policy, "2026-06-15", "2026-09-01")).isEqualTo("100000.00");
            assertContiguousChain(policy);
        }

        @Test
        void refusesToEndorseAPolicyThatWasAlreadyCancelledOnThatDate() {
            Policy policy = cancelled();

            assertThatExceptionOfType(PolicyNotInForceException.class)
                    .isThrownBy(() -> policy.endorse(
                            date("2026-10-01"),
                            instant("2026-10-02"),
                            "Priya Raman",
                            "TX-DALLAS",
                            "too late",
                            List.of(collision("10000.00"))))
                    .withMessageContaining("cancelled");
        }

        @Test
        void allowsAnEndorsementEffectiveBeforeTheCancellation() {
            Policy policy = cancelled();

            policy.endorse(
                    date("2026-05-01"),
                    instant("2026-10-02"),
                    "Priya Raman",
                    "TX-DALLAS",
                    "Retroactive correction to a period that was on risk",
                    List.of(collision("60000.00")));

            assertThat(collisionLimitOn(policy, "2026-06-01", "2026-11-01")).isEqualTo("60000.00");
            assertThat(collisionLimitOn(policy, "2026-09-15", "2026-11-01")).isNull();
            assertContiguousChain(policy);
        }
    }

    @Nested
    @DisplayName("operations outside the policy period")
    class OutsidePolicyPeriod {

        @Test
        void refusesAnEndorsementEffectiveBeforeInception() {
            Policy policy = annualAutoPolicy("50000.00");

            assertThatExceptionOfType(PolicyNotInForceException.class)
                    .isThrownBy(() -> policy.endorse(
                            date("2025-11-01"),
                            instant("2026-02-01"),
                            "Priya Raman",
                            "TX-DALLAS",
                            "before the policy existed",
                            List.of(collision("10000.00"))))
                    .withMessageContaining("no version was in force");
        }

        @Test
        void refusesAnEndorsementEffectiveAfterExpiry() {
            Policy policy = annualAutoPolicy("50000.00");

            assertThatExceptionOfType(PolicyNotInForceException.class)
                    .isThrownBy(() -> policy.endorse(
                            date("2027-02-01"),
                            instant("2027-02-01"),
                            "Priya Raman",
                            "TX-DALLAS",
                            "after expiry",
                            List.of(collision("10000.00"))));
        }
    }

    /**
     * Asserts the invariant every write is supposed to preserve: the versions currently believed
     * tile their span with no gaps and no overlaps. A gap means an uncovered day inside the
     * policy period; an overlap means two contradictory answers to the same coverage question.
     */
    private static void assertContiguousChain(Policy policy) {
        List<PolicyVersion> chain = policy.currentChain();
        assertThat(chain).isNotEmpty();

        for (int i = 0; i < chain.size() - 1; i++) {
            LocalDate endOfThis = chain.get(i).effectiveTo();
            LocalDate startOfNext = chain.get(i + 1).effectiveFrom();
            assertThat(endOfThis)
                    .as("version %d must end where version %d begins", i, i + 1)
                    .isEqualTo(startOfNext);
        }
        // Only the final link may run to infinity.
        assertThat(chain.subList(0, chain.size() - 1)).allMatch(v -> v.effectiveTo() != null);
    }
}
