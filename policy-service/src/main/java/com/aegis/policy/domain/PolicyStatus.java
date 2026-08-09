package com.aegis.policy.domain;

/** Whether the policy is on risk during a version's valid-time period. */
public enum PolicyStatus {
    /** Coverage applies. */
    IN_FORCE,
    /** The policy was cancelled; no coverage applies from this version's effective date. */
    CANCELLED
}
