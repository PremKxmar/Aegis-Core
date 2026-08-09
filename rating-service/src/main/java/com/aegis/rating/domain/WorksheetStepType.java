package com.aegis.rating.domain;

/**
 * The kinds of line that appear on a rating worksheet, in the order they are always applied.
 *
 * <p>The order is part of the answer, not an implementation detail. Multiplication is
 * commutative, but rounding is not: applying the same factors in a different order and rounding
 * at each step lands on a different premium. Fixing the order here, and rounding only once at the
 * end, is what makes the same input reproduce the same output every time.
 */
public enum WorksheetStepType {
    /** Additive: one line per coverage, {@code ratePerThousand x limit / 1000}. */
    BASE_RATE,
    /** Multiplicative, by geography. */
    TERRITORY_FACTOR,
    /** Multiplicative, by the age of the insured asset. */
    ASSET_AGE_FACTOR,
    /** Multiplicative, by the number of prior at-fault claims. */
    CLAIMS_HISTORY_SURCHARGE,
    /** Multiplicative and always {@code <= 1}, applied when the insured holds more than one policy. */
    MULTI_POLICY_DISCOUNT,
    /** A floor, not a factor. The last word: no combination of discounts goes below it. */
    MINIMUM_PREMIUM_FLOOR
}
