package com.aegis.policy.domain;

/** What produced a {@link PolicyVersion}. */
public enum ChangeType {
    /** The first version, created when the policy was bound. */
    BOUND,
    /** A mid-term change to the terms, effective from a date that may be in the past. */
    ENDORSED,
    /** A terminal version: the policy is off risk from its effective date onward. */
    CANCELLED,
    /**
     * A version written solely to close off the valid-time period of an earlier one.
     *
     * <p>When a backdated endorsement lands in the middle of an existing version's validity,
     * that version has to be split. The earlier fragment keeps its original terms but ends
     * sooner, and it is recorded as a new row rather than by editing the old one — the whole
     * point of the model is that a row, once written, never changes its terms.
     */
    SPLIT
}
