package com.aegis.policy.domain;

/** Thrown when no policy exists with the given number. */
public class PolicyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String policyNumber;

    public PolicyNotFoundException(String policyNumber) {
        super("No policy found with number " + policyNumber);
        this.policyNumber = policyNumber;
    }

    public String policyNumber() {
        return policyNumber;
    }
}
