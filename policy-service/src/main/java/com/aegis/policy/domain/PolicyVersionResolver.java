package com.aegis.policy.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves which version of a policy applies, in both time dimensions at once.
 *
 * <p>This is the heart of the system. Everything else — binding, endorsing, cancelling, the
 * REST API, the claims integration — exists so that this question can be answered correctly:
 *
 * <blockquote>
 * As we understood things at instant {@code asAt}, which terms applied on date {@code asOf}?
 * </blockquote>
 *
 * <p>Deliberately a pure static function over a collection rather than a repository method.
 * The same logic is expressed twice — here in Java and in SQL in {@code PolicyVersionRepository}
 * — and an integration test asserts the two agree on every case in the resolution table. Keeping
 * this half pure means the whole space of overlapping, backdated and cancelled histories can be
 * unit-tested in milliseconds without a database.
 */
public final class PolicyVersionResolver {

    private PolicyVersionResolver() {
        // static utility
    }

    /**
     * The version in force on {@code asOf}, as believed at {@code asAt}.
     *
     * @param versions every version of one policy, current and superseded alike. Passing only
     *     the current ones would silently answer historical questions with today's beliefs.
     * @param asOf the valid-time date — for a claim, the loss date
     * @param asAt the transaction-time instant — "as we understood it then". Pass
     *     {@code Instant.now()} for the current understanding.
     * @return the single applicable version, or empty if the policy did not exist on that date
     * @throws AmbiguousPolicyHistoryException if more than one version matches, which means the
     *     write path has produced overlapping periods. Returning an arbitrary one of them would
     *     turn a data-integrity bug into a wrong claim decision that nobody ever notices.
     */
    public static Optional<PolicyVersion> resolve(Collection<PolicyVersion> versions, LocalDate asOf, Instant asAt) {
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(asAt, "asAt");

        List<PolicyVersion> matches = versions.stream()
                .filter(v -> v.wasBelievedAt(asAt))
                .filter(v -> v.isValidOn(asOf))
                .toList();

        if (matches.size() > 1) {
            throw new AmbiguousPolicyHistoryException(asOf, asAt, matches);
        }
        return matches.stream().findFirst();
    }

    /**
     * The chain of versions the system currently believes, in effective-date order.
     *
     * <p>Ordered by effective date rather than by version number on purpose: a backdated
     * endorsement carries a higher version number than the terms it precedes, so version number
     * is not a timeline.
     */
    public static List<PolicyVersion> currentChain(Collection<PolicyVersion> versions) {
        return versions.stream()
                .filter(PolicyVersion::isCurrentBelief)
                .sorted(Comparator.comparing(PolicyVersion::effectiveFrom))
                .toList();
    }
}
