package com.aegis.rating.domain;

/**
 * The risk cannot be rated with the selected rate table — an unrated coverage or territory, an
 * age or claim count no band covers, or a currency mismatch.
 *
 * <p>Always a refusal, never a fallback. Every alternative to failing here (defaulting a missing
 * factor to 1.0, defaulting a missing base rate to zero, picking one of two overlapping bands)
 * produces a number that looks like a premium, is wrong, and carries no signal that it is wrong.
 */
public class UnratableRiskException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnratableRiskException(String message) {
        super(message);
    }
}
