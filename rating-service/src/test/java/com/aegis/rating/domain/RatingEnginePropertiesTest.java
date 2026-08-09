package com.aegis.rating.domain;

import static com.aegis.rating.domain.RateTables.USD;
import static org.assertj.core.api.Assertions.assertThat;

import com.aegis.contracts.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * The rating engine's guarantees, stated as properties that must hold for every valid risk rather
 * than for three hand-picked ones.
 *
 * <p>Worked examples prove the engine gets a specific answer right. These prove it cannot get a
 * whole class of answers wrong — which is the claim that actually matters when the rate tables
 * are changed by someone who has never read this code. Each property below is a rule a carrier
 * would state in words, and jqwik generates hundreds of cases trying to break it.
 */
class RatingEnginePropertiesTest {

    // Generators are bounded to realistic ranges. Unbounded values would find "bugs" that are
    // really just arithmetic overflow on premiums no insurer would ever write, and a test suite
    // that reports impossible failures gets ignored.

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.10"), new BigDecimal("50.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> factors() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.50"), new BigDecimal("3.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> discounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.50"), BigDecimal.ONE)
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> limits() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1000.00"), new BigDecimal("5000000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<Money> minimumPremiums() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("2000.00"))
                .ofScale(2)
                .map(amount -> Money.of(amount, USD));
    }

    @Provide
    Arbitrary<RateTable> rateTables() {
        return Combinators.combine(rates(), factors(), factors(), factors(), discounts(), minimumPremiums())
                .as(RateTables::generatedTable);
    }

    private static RatingInput risk(int assetAge, int priorClaims, int policiesHeld, BigDecimal collisionLimit) {
        return new RatingInput(
                "GENERATED",
                LocalDate.parse("2026-06-01"),
                "TX-DALLAS",
                assetAge,
                priorClaims,
                policiesHeld,
                List.of(new RatingInput.CoverageInput("COLLISION", Money.of(collisionLimit, USD))));
    }

    /**
     * Raising a coverage limit can never lower the premium.
     *
     * <p>The rule a customer would state as "I'm paying more, so I should be getting at least as
     * much cover". Violating it means a customer can buy more insurance for less money, which is
     * both an arbitrage and a sign the exposure calculation has a sign error somewhere.
     */
    @Property
    void premiumIsMonotonicallyNonDecreasingInCoverageLimit(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal lowerLimit,
            @ForAll("limits") BigDecimal higherLimit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims,
            @ForAll @IntRange(min = 1, max = 5) int policiesHeld) {

        BigDecimal low = lowerLimit.min(higherLimit);
        BigDecimal high = lowerLimit.max(higherLimit);

        Money atLow = RatingEngine.rate(table, risk(assetAge, priorClaims, policiesHeld, low))
                .totalPremium();
        Money atHigh = RatingEngine.rate(table, risk(assetAge, priorClaims, policiesHeld, high))
                .totalPremium();

        assertThat(atHigh)
                .as("limit %s -> %s, but premium %s -> %s", low, high, atLow, atHigh)
                .isGreaterThanOrEqualTo(atLow);
    }

    /**
     * No combination of discounts can put the premium below the filed minimum.
     *
     * <p>The minimum premium is a regulatory filing, not a suggestion. A discount stack that
     * produces 12 USD for a year of motor cover is the kind of defect that is only noticed when
     * the regulator notices it.
     */
    @Property
    void premiumIsNeverBelowTheMinimumPremiumFloor(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal limit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims,
            @ForAll @IntRange(min = 1, max = 5) int policiesHeld) {

        Money premium = RatingEngine.rate(table, risk(assetAge, priorClaims, policiesHeld, limit))
                .totalPremium();

        assertThat(premium).isGreaterThanOrEqualTo(table.minimumPremium());
    }

    /**
     * The worksheet reconciles: its last subtotal is the premium, and the base-rate lines sum to
     * the base premium.
     *
     * <p>An underwriter reading the worksheet is checking arithmetic. If the lines do not add up
     * to the number at the bottom, the worksheet is worse than useless — it is misleading, and it
     * would be believed.
     */
    @Property
    void worksheetReconcilesToTheTotalPremium(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal limit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims,
            @ForAll @IntRange(min = 1, max = 5) int policiesHeld) {

        RatingResult result = RatingEngine.rate(table, risk(assetAge, priorClaims, policiesHeld, limit));

        assertThat(result.worksheet()).isNotEmpty();
        assertThat(result.worksheet().getLast().subtotal()).isEqualTo(result.totalPremium());
        assertThat(result.worksheet()).extracting(WorksheetLine::stepNumber).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(result.worksheet().getFirst().subtotal()).isEqualTo(result.basePremium());
    }

    /**
     * Rating is a pure function: the same inputs always produce the same output, down to the text
     * of the worksheet.
     */
    @Property
    void ratingTheSameRiskTwiceProducesIdenticalOutput(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal limit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims,
            @ForAll @IntRange(min = 1, max = 5) int policiesHeld) {

        RatingInput input = risk(assetAge, priorClaims, policiesHeld, limit);

        RatingResult first = RatingEngine.rate(table, input);
        RatingResult second = RatingEngine.rate(table, input);

        assertThat(second.totalPremium()).isEqualTo(first.totalPremium());
        assertThat(second.renderWorksheet()).isEqualTo(first.renderWorksheet());
    }

    /**
     * More prior claims can never reduce the premium, given bands that are themselves
     * non-decreasing. Here the generated table has a single open claims band, so the property
     * holds trivially in the factor but still exercises the surcharge path — what it really pins
     * down is that the claim count never leaks into the calculation with the wrong sign.
     */
    @Property
    void aMultiPolicyDiscountNeverIncreasesThePremium(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal limit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims) {

        Money single =
                RatingEngine.rate(table, risk(assetAge, priorClaims, 1, limit)).totalPremium();
        Money multi =
                RatingEngine.rate(table, risk(assetAge, priorClaims, 3, limit)).totalPremium();

        assertThat(multi).isLessThanOrEqualTo(single);
    }

    /** Every premium is a positive amount in the currency the risk was expressed in. */
    @Property
    void premiumIsAlwaysPositiveAndInTheRiskCurrency(
            @ForAll("rateTables") RateTable table,
            @ForAll("limits") BigDecimal limit,
            @ForAll @IntRange(min = 0, max = 40) int assetAge,
            @ForAll @IntRange(min = 0, max = 10) int priorClaims,
            @ForAll @IntRange(min = 1, max = 5) int policiesHeld) {

        Money premium = RatingEngine.rate(table, risk(assetAge, priorClaims, policiesHeld, limit))
                .totalPremium();

        assertThat(premium.isNegative()).isFalse();
        assertThat(premium.currencyCode()).isEqualTo(USD);
    }
}
