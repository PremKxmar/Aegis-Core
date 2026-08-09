package com.aegis.policy.domain;

import java.time.LocalDate;

/**
 * Thrown when an operation is attempted against a date on which the policy was not on risk —
 * before inception, after expiry, or after a cancellation.
 *
 * <p>Carries the date and the reason separately from the message because claims-service turns
 * this into a coverage decision that has to be explained to a claimant.
 */
public class PolicyNotInForceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String policyNumber;
    private final LocalDate date;

    public PolicyNotInForceException(String policyNumber, LocalDate date, String reason) {
        super("Policy " + policyNumber + " was not in force on " + date + ": " + reason);
        this.policyNumber = policyNumber;
        this.date = date;
    }

    public String policyNumber() {
        return policyNumber;
    }

    public LocalDate date() {
        return date;
    }
}
