package com.aegis.policy.domain;

/** Thrown when binding a policy whose number is already taken. */
public class DuplicatePolicyNumberException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String policyNumber;

    public DuplicatePolicyNumberException(String policyNumber) {
        super("A policy already exists with number " + policyNumber);
        this.policyNumber = policyNumber;
    }

    public String policyNumber() {
        return policyNumber;
    }
}
