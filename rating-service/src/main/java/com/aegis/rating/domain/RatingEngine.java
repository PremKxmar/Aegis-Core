package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a risk into a premium, and shows its working.
 *
 * <p><b>Deterministic by construction.</b> A pure static function of {@code (rateTable, input)}.
 * No clock, no database, no randomness, no ambient state. Rate the same risk against the same
 * table version a year apart and the bytes are identical — which is the whole reason a premium
 * can be defended to a regulator or an angry policyholder.
 *
 * <p><b>Rounds exactly once.</b> All intermediate arithmetic runs at 16 significant digits and is
 * converted to {@link Money} only at the end. Rounding to cents after every factor would make the
 * result depend on the order the factors happened to be applied in, and would drift by cents
 * against a hand calculation — the classic way a rating engine stops being reproducible.
 *
 * <p><b>Order is fixed.</b> Base rates are summed, then territory, asset age, claims history and
 * multi-policy discount multiply the running value in that order, then the minimum premium floor
 * has the last word. The floor is applied last on purpose: applied before the discount, a
 * discount could take the premium below the minimum the carrier filed.
 */
public final class RatingEngine {

    /**
     * 16 significant digits — enough that chained factor multiplication cannot lose a cent on any
     * realistic premium, while remaining a bounded, reproducible precision rather than the
     * unbounded exactness that division would demand.
     */
    static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_EVEN);

    private static final BigDecimal PER_THOUSAND = new BigDecimal("1000");

    private RatingEngine() {
        // static utility
    }

    /**
     * @throws UnratableRiskException if the table cannot rate some part of the risk
     */
    public static RatingResult rate(RateTable rateTable, RatingInput input) {
        String currency = input.currency();
        requireCurrenciesAgree(rateTable, currency);

        List<WorksheetLine> worksheet = new ArrayList<>();
        int step = 0;

        // --- Base rates, summed. One line per coverage. -------------------------------------
        // Sorted by coverage code so the worksheet is identical for the same set of coverages
        // regardless of the order the caller listed them in. Two quotes for the same risk that
        // differ only in line order would be indistinguishable to a human and different to a
        // byte comparison, which defeats the reproducibility this class exists to provide.
        BigDecimal running = BigDecimal.ZERO;
        for (RatingInput.CoverageInput coverage : input.coverages().stream()
                .sorted(Comparator.comparing(RatingInput.CoverageInput::coverageCode))
                .toList()) {

            BigDecimal ratePerThousand = rateTable.ratePerThousand(coverage.coverageCode());
            BigDecimal exposureUnits = coverage.limit().amount().divide(PER_THOUSAND, PRECISION);
            BigDecimal lineAmount = ratePerThousand.multiply(exposureUnits, PRECISION);
            running = running.add(lineAmount, PRECISION);

            worksheet.add(new WorksheetLine(
                    ++step,
                    WorksheetStepType.BASE_RATE,
                    "Base rate for " + coverage.coverageCode() + " at "
                            + ratePerThousand.stripTrailingZeros().toPlainString() + " per 1,000",
                    coverage.limit() + " limit",
                    null,
                    Money.of(running, currency)));
        }

        // --- Multiplicative factors, in a fixed order. ---------------------------------------
        BigDecimal territoryFactor = rateTable.territoryFactor(input.territory());
        running = running.multiply(territoryFactor, PRECISION);
        worksheet.add(new WorksheetLine(
                ++step,
                WorksheetStepType.TERRITORY_FACTOR,
                "Territory factor",
                input.territory(),
                territoryFactor,
                Money.of(running, currency)));

        BigDecimal ageFactor = rateTable.assetAgeFactor(input.assetAgeYears());
        running = running.multiply(ageFactor, PRECISION);
        worksheet.add(new WorksheetLine(
                ++step,
                WorksheetStepType.ASSET_AGE_FACTOR,
                "Asset age factor",
                input.assetAgeYears() + " years",
                ageFactor,
                Money.of(running, currency)));

        BigDecimal claimsFactor = rateTable.claimsHistoryFactor(input.priorClaimsCount());
        running = running.multiply(claimsFactor, PRECISION);
        worksheet.add(new WorksheetLine(
                ++step,
                WorksheetStepType.CLAIMS_HISTORY_SURCHARGE,
                "Claims history surcharge",
                input.priorClaimsCount() + " prior claims",
                claimsFactor,
                Money.of(running, currency)));

        // The discount line is always written, even at 1.0. A worksheet whose lines appear and
        // disappear depending on the answer is much harder to diff between two quotes, and
        // "no discount applied" is itself information the reader wants.
        BigDecimal discountFactor =
                input.qualifiesForMultiPolicyDiscount() ? rateTable.multiPolicyDiscountFactor() : BigDecimal.ONE;
        running = running.multiply(discountFactor, PRECISION);
        worksheet.add(new WorksheetLine(
                ++step,
                WorksheetStepType.MULTI_POLICY_DISCOUNT,
                input.qualifiesForMultiPolicyDiscount()
                        ? "Multi-policy discount"
                        : "Multi-policy discount (not applicable)",
                input.policiesHeld() + " policies held",
                discountFactor,
                Money.of(running, currency)));

        // --- The floor has the last word. -----------------------------------------------------
        Money beforeFloor = Money.of(running, currency);
        Money minimumPremium = rateTable.minimumPremium();
        Money totalPremium = beforeFloor.max(minimumPremium);

        worksheet.add(new WorksheetLine(
                ++step,
                WorksheetStepType.MINIMUM_PREMIUM_FLOOR,
                totalPremium.equals(beforeFloor)
                        ? "Minimum premium floor (not binding)"
                        : "Minimum premium floor APPLIED",
                "floor " + minimumPremium,
                null,
                totalPremium));

        return new RatingResult(totalPremium, worksheet, rateTable);
    }

    private static void requireCurrenciesAgree(RateTable rateTable, String currency) {
        if (!rateTable.minimumPremium().currencyCode().equals(currency)) {
            throw new UnratableRiskException("Rate table " + rateTable.describe() + " prices in "
                    + rateTable.minimumPremium().currencyCode() + " but the risk is in " + currency
                    + "; this engine does not convert currencies");
        }
    }
}
