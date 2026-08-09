package com.aegis.policy.api;

import java.time.LocalDate;

/**
 * The policy exists but has no version valid on the requested date — before inception, or after
 * expiry.
 *
 * <p>Distinct from {@code PolicyNotFoundException} so the caller can tell "wrong policy number"
 * from "right policy, wrong date". Both are 404s on the wire, but the problem detail differs and
 * the two lead an operator to entirely different places.
 */
public class NoVersionInForceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String policyNumber;
    private final LocalDate asOf;

    public NoVersionInForceException(String policyNumber, LocalDate asOf) {
        super("Policy " + policyNumber + " has no version in force on " + asOf);
        this.policyNumber = policyNumber;
        this.asOf = asOf;
    }

    public String policyNumber() {
        return policyNumber;
    }

    public LocalDate asOf() {
        return asOf;
    }
}
