package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the rating engine is allowed to see.
 *
 * <p>Note what is absent: there is no clock, no database handle and no "current date". The
 * effective date is an input, so re-rating a risk for 1 March next year produces the same premium
 * it produced last year. An engine that reads the wall clock cannot be reproduced, and a premium
 * that cannot be reproduced cannot be explained to the person paying it.
 *
 * @param effectiveDate the date to rate as of — selects which rate table version applies
 * @param assetAgeYears age of the insured asset in whole years: the vehicle, or the property
 * @param priorClaimsCount at-fault claims in the look-back period
 * @param policiesHeld total policies the insured holds with the carrier, including this one
 */
public record RatingInput(
        String productCode,
        LocalDate effectiveDate,
        String territory,
        int assetAgeYears,
        int priorClaimsCount,
        int policiesHeld,
        List<CoverageInput> coverages) {

    public RatingInput {
        if (assetAgeYears < 0) {
            throw new UnratableRiskException("assetAgeYears must not be negative, got " + assetAgeYears);
        }
        if (priorClaimsCount < 0) {
            throw new UnratableRiskException("priorClaimsCount must not be negative, got " + priorClaimsCount);
        }
        if (policiesHeld < 1) {
            throw new UnratableRiskException("policiesHeld must be at least 1 (this policy), got " + policiesHeld);
        }
        if (coverages == null || coverages.isEmpty()) {
            throw new UnratableRiskException("At least one coverage must be rated");
        }
        coverages = List.copyOf(coverages);
    }

    /** Whether the multi-policy discount applies. */
    public boolean qualifiesForMultiPolicyDiscount() {
        return policiesHeld > 1;
    }

    /**
     * The currency every amount in this quote is denominated in.
     *
     * @throws UnratableRiskException if the coverages do not agree on one. A premium is a single
     *     amount, so it has a single currency, and quietly rating a mixed-currency risk would
     *     produce a number whose units are undefined.
     */
    public String currency() {
        List<String> distinct =
                coverages.stream().map(c -> c.limit().currencyCode()).distinct().toList();
        if (distinct.size() > 1) {
            throw new UnratableRiskException("All coverages must share one currency, got " + distinct);
        }
        return distinct.getFirst();
    }

    /** @param limit the coverage limit; the base rate is charged per 1,000 of it */
    public record CoverageInput(String coverageCode, Money limit) {}
}
