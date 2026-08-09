package com.aegis.rating.domain;

import static com.aegis.rating.domain.RateTables.USD;
import static com.aegis.rating.domain.RateTables.standardAutoTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.aegis.contracts.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Worked examples, with the arithmetic spelled out so the expected numbers can be checked by
 * hand rather than copied from a previous run.
 */
class RatingEngineTest {

    private static RatingInput input(
            String territory, int assetAge, int priorClaims, int policiesHeld, String collisionLimit) {
        return new RatingInput(
                "PERSONAL_AUTO",
                LocalDate.parse("2026-06-01"),
                territory,
                assetAge,
                priorClaims,
                policiesHeld,
                List.of(new RatingInput.CoverageInput("COLLISION", Money.of(collisionLimit, USD))));
    }

    @Nested
    @DisplayName("the arithmetic")
    class Arithmetic {

        @Test
        void computesASinglePremiumFromASingleCoverage() {
            /*
             * COLLISION limit 50,000 at 4.00 per 1,000  = 50 x 4.00       = 200.00
             * territory TX-DALLAS                        x 1.20            = 240.00
             * asset age 2 years (band 0..5)              x 1.00            = 240.00
             * prior claims 0     (band 0..1)             x 1.00            = 240.00
             * policies held 1 -> no discount             x 1.00            = 240.00
             * minimum premium 250.00 -> FLOOR APPLIES                      = 250.00
             */
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "50000.00"));

            assertThat(result.totalPremium()).isEqualTo(Money.of("250.00", USD));
            assertThat(result.basePremium()).isEqualTo(Money.of("200.00", USD));
        }

        @Test
        void appliesEveryFactorInOrder() {
            /*
             * COLLISION limit 200,000 at 4.00 per 1,000 = 200 x 4.00      = 800.00
             * territory TX-DALLAS                        x 1.20            = 960.00
             * asset age 7 years (band 5..)               x 1.50            = 1440.00
             * prior claims 2    (band 1..)               x 2.00            = 2880.00
             * policies held 3 -> discount                x 0.90            = 2592.00
             * minimum premium 250.00 -> not binding                        = 2592.00
             */
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 7, 2, 3, "200000.00"));

            assertThat(result.totalPremium()).isEqualTo(Money.of("2592.00", USD));
            assertThat(result.worksheet())
                    .extracting(WorksheetLine::subtotal)
                    .containsExactly(
                            Money.of("800.00", USD),
                            Money.of("960.00", USD),
                            Money.of("1440.00", USD),
                            Money.of("2880.00", USD),
                            Money.of("2592.00", USD),
                            Money.of("2592.00", USD));
        }

        @Test
        void sumsBaseRatesAcrossSeveralCoverages() {
            /*
             * COLLISION     100,000 at 4.00/1000 = 400.00
             * COMPREHENSIVE  50,000 at 2.00/1000 = 100.00   base total     = 500.00
             * territory IA-RURAL                  x 0.80                   = 400.00
             * remaining factors all 1.00, no discount, floor not binding    = 400.00
             */
            RatingResult result = RatingEngine.rate(
                    standardAutoTable(),
                    new RatingInput(
                            "PERSONAL_AUTO",
                            LocalDate.parse("2026-06-01"),
                            "IA-RURAL",
                            1,
                            0,
                            1,
                            List.of(
                                    new RatingInput.CoverageInput("COLLISION", Money.of("100000.00", USD)),
                                    new RatingInput.CoverageInput("COMPREHENSIVE", Money.of("50000.00", USD)))));

            assertThat(result.basePremium()).isEqualTo(Money.of("500.00", USD));
            assertThat(result.totalPremium()).isEqualTo(Money.of("400.00", USD));
        }

        @Test
        void appliesTheFloorAfterTheDiscountRatherThanBefore() {
            // Base 200,000 x 4.00/1000 = 800; territory 1.20 -> 960; age 1.00; claims 1.00;
            // discount 0.90 -> 864. If the floor had been applied before the discount, the
            // discount would then have pulled the premium under the filed minimum.
            RatingResult withDiscount =
                    RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 2, "200000.00"));

            assertThat(withDiscount.totalPremium()).isEqualTo(Money.of("864.00", USD));
            assertThat(withDiscount.totalPremium()).isGreaterThanOrEqualTo(Money.of("250.00", USD));
        }

        @Test
        void roundsOnlyOnceAtTheEnd() {
            // 33,333 limit at 4.00/1000 = 133.332, then x1.20 x1.00 x1.00 = 159.9984.
            // Rounding each step to cents would give 133.33 -> 159.996 -> 160.00.
            // Rounding once at the end gives 160.00 as well here, but the intermediate
            // worksheet line must show the UNROUNDED chain's value, not a re-rounded one.
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "33333.00"));

            assertThat(result.basePremium()).isEqualTo(Money.of("133.33", USD));
            // 159.9984 rounds HALF_EVEN to 160.00, and the floor of 250.00 then binds.
            assertThat(result.totalPremium()).isEqualTo(Money.of("250.00", USD));
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void producesIdenticalOutputForIdenticalInput() {
            RateTable table = standardAutoTable();
            RatingInput risk = input("TX-DALLAS", 7, 2, 3, "137500.00");

            RatingResult first = RatingEngine.rate(table, risk);
            RatingResult second = RatingEngine.rate(table, risk);

            assertThat(second.totalPremium()).isEqualTo(first.totalPremium());
            assertThat(second.renderWorksheet()).isEqualTo(first.renderWorksheet());
        }

        @Test
        void ignoresTheOrderCoveragesWereListedIn() {
            // Two quotes for the same risk that differ only in the order the caller happened to
            // list coverages must be byte-identical, or "same input, same output" is not true.
            RateTable table = standardAutoTable();
            var collision = new RatingInput.CoverageInput("COLLISION", Money.of("100000.00", USD));
            var comprehensive = new RatingInput.CoverageInput("COMPREHENSIVE", Money.of("50000.00", USD));

            RatingResult forwards = RatingEngine.rate(
                    table,
                    new RatingInput(
                            "PERSONAL_AUTO",
                            LocalDate.parse("2026-06-01"),
                            "TX-DALLAS",
                            2,
                            0,
                            1,
                            List.of(collision, comprehensive)));
            RatingResult backwards = RatingEngine.rate(
                    table,
                    new RatingInput(
                            "PERSONAL_AUTO",
                            LocalDate.parse("2026-06-01"),
                            "TX-DALLAS",
                            2,
                            0,
                            1,
                            List.of(comprehensive, collision)));

            assertThat(backwards.renderWorksheet()).isEqualTo(forwards.renderWorksheet());
        }
    }

    @Nested
    @DisplayName("the worksheet")
    class Worksheet {

        @Test
        void reconcilesToTheTotalPremium() {
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 7, 2, 3, "200000.00"));

            assertThat(result.worksheet().getLast().subtotal()).isEqualTo(result.totalPremium());
        }

        @Test
        void numbersItsStepsFromOneWithNoGaps() {
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 7, 2, 3, "200000.00"));

            assertThat(result.worksheet()).extracting(WorksheetLine::stepNumber).containsExactly(1, 2, 3, 4, 5, 6);
        }

        @Test
        void alwaysRecordsEveryRuleEvenWhenItDidNotChangeTheAnswer() {
            // A worksheet whose lines appear and disappear depending on the outcome is far
            // harder to diff between two quotes, and "no discount applied" is information.
            RatingResult noDiscount = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "200000.00"));

            assertThat(noDiscount.worksheet())
                    .extracting(WorksheetLine::type)
                    .containsExactly(
                            WorksheetStepType.BASE_RATE,
                            WorksheetStepType.TERRITORY_FACTOR,
                            WorksheetStepType.ASSET_AGE_FACTOR,
                            WorksheetStepType.CLAIMS_HISTORY_SURCHARGE,
                            WorksheetStepType.MULTI_POLICY_DISCOUNT,
                            WorksheetStepType.MINIMUM_PREMIUM_FLOOR);
            assertThat(noDiscount.worksheet().get(4).description()).contains("not applicable");
            assertThat(noDiscount.worksheet().get(4).factorValue()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        void normalisesEveryFactorToTheScaleTheDatabaseStores() {
            // BigDecimal.equals is scale-sensitive, and the factor column is NUMERIC(19,6). If the
            // engine emitted an unscaled 1 for "no discount", a worksheet read back from the
            // database would not equal the one just computed — which would quietly falsify the
            // reproducibility this service promises.
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "200000.00"));

            assertThat(result.worksheet())
                    .filteredOn(line -> line.factorValue() != null)
                    .allSatisfy(line -> assertThat(line.factorValue().scale())
                            .as("factor on step %d", line.stepNumber())
                            .isEqualTo(WorksheetLine.FACTOR_SCALE));
        }

        @Test
        void saysWhetherTheFloorActuallyBound() {
            RatingResult floored = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "10000.00"));
            RatingResult notFloored = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 2, 0, 1, "200000.00"));

            assertThat(floored.worksheet().getLast().description()).contains("APPLIED");
            assertThat(notFloored.worksheet().getLast().description()).contains("not binding");
        }

        @Test
        void rendersALineForEveryStepWithItsInputAndFactor() {
            RatingResult result = RatingEngine.rate(standardAutoTable(), input("TX-DALLAS", 7, 2, 3, "200000.00"));

            String rendered = result.renderWorksheet();

            assertThat(rendered)
                    .contains("Rate table: PERSONAL_AUTO v1")
                    .contains("Base rate for COLLISION at 4 per 1,000")
                    .contains("Territory factor [TX-DALLAS]  x1.2")
                    .contains("Asset age factor [7 years]  x1.5")
                    .contains("Claims history surcharge [2 prior claims]  x2")
                    .contains("Multi-policy discount [3 policies held]  x0.9")
                    .contains("TOTAL PREMIUM: 2592.00 USD");
        }
    }

    @Nested
    @DisplayName("refusing to rate")
    class Refusals {

        @Test
        void refusesACoverageTheTableDoesNotRate() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> RatingEngine.rate(
                            standardAutoTable(),
                            new RatingInput(
                                    "PERSONAL_AUTO",
                                    LocalDate.parse("2026-06-01"),
                                    "TX-DALLAS",
                                    2,
                                    0,
                                    1,
                                    List.of(new RatingInput.CoverageInput("FLOOD", Money.of("1000.00", USD))))))
                    .withMessageContaining("no base rate for coverage 'FLOOD'");
        }

        @Test
        void refusesAnUnratedTerritory() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(
                            () -> RatingEngine.rate(standardAutoTable(), input("MARS-OLYMPUS", 2, 0, 1, "50000.00")))
                    .withMessageContaining("does not rate territory 'MARS-OLYMPUS'");
        }

        @Test
        void refusesRatherThanGuessingWhenBandsOverlap() {
            // Two bands both covering age 7 is a data error. Picking one produces a plausible
            // premium that is wrong in a way nothing downstream can detect.
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> RatingEngine.rate(
                            RateTables.tableWithOverlappingAgeBands(), input("TX-DALLAS", 7, 0, 1, "50000.00")))
                    .withMessageContaining("overlapping age bands");
        }

        @Test
        void refusesWhenNoBandCoversTheRisk() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> RatingEngine.rate(
                            RateTables.tableWithGapInAgeBands(), input("TX-DALLAS", 7, 0, 1, "50000.00")))
                    .withMessageContaining("no age band covering 7 years");
        }

        @Test
        void refusesToConvertCurrencies() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(
                            () -> RatingEngine.rate(RateTables.euroTable(), input("TX-DALLAS", 2, 0, 1, "50000.00")))
                    .withMessageContaining("does not convert currencies");
        }

        @Test
        void refusesARiskWhoseCoveragesMixCurrencies() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> new RatingInput(
                                    "PERSONAL_AUTO",
                                    LocalDate.parse("2026-06-01"),
                                    "TX-DALLAS",
                                    2,
                                    0,
                                    1,
                                    List.of(
                                            new RatingInput.CoverageInput("COLLISION", Money.of("1000.00", USD)),
                                            new RatingInput.CoverageInput("COMPREHENSIVE", Money.of("1000.00", "EUR"))))
                            .currency())
                    .withMessageContaining("must share one currency");
        }

        @Test
        void refusesNonsensicalInputs() {
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> input("TX-DALLAS", -1, 0, 1, "50000.00"))
                    .withMessageContaining("assetAgeYears");
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> input("TX-DALLAS", 2, -1, 1, "50000.00"))
                    .withMessageContaining("priorClaimsCount");
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> input("TX-DALLAS", 2, 0, 0, "50000.00"))
                    .withMessageContaining("policiesHeld");
            assertThatExceptionOfType(UnratableRiskException.class)
                    .isThrownBy(() -> new RatingInput(
                            "PERSONAL_AUTO", LocalDate.parse("2026-06-01"), "TX-DALLAS", 2, 0, 1, List.of()))
                    .withMessageContaining("At least one coverage");
        }
    }

    @Nested
    @DisplayName("rate table effective dating")
    class EffectiveDating {

        @Test
        void isEffectiveOverAHalfOpenPeriod() {
            RateTable table = standardAutoTable();

            assertThat(table.isEffectiveOn(LocalDate.parse("2025-12-31"))).isFalse();
            assertThat(table.isEffectiveOn(LocalDate.parse("2026-01-01"))).isTrue();
            assertThat(table.isEffectiveOn(LocalDate.parse("2030-01-01"))).isTrue();
        }
    }
}
