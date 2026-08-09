package com.aegis.policy.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when two versions of the same policy claim the same point in both time dimensions.
 *
 * <p>This is an invariant violation, not a user error: the write path is supposed to make it
 * impossible. It is surfaced as a 500 rather than swallowed because the alternative — picking
 * one of the overlapping versions arbitrarily — produces a plausible-looking coverage answer
 * that is wrong, and nothing downstream would ever flag it.
 */
public class AmbiguousPolicyHistoryException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AmbiguousPolicyHistoryException(LocalDate asOf, Instant asAt, List<PolicyVersion> matches) {
        super("Policy history is ambiguous: " + matches.size() + " versions are valid on " + asOf
                + " as believed at " + asAt + ". Overlapping versions: "
                + matches.stream().map(PolicyVersion::toString).collect(Collectors.joining(", ")));
    }
}
