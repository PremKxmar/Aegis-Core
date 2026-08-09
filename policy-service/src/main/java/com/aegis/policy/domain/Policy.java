package com.aegis.policy.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A policy: an identity, plus the append-only chain of versions that describes everything that
 * has ever been true about it.
 *
 * <p>The row itself holds no terms. Limits, deductibles, the insured's name and the territory
 * all live on {@link PolicyVersion}, because all of them can change mid-term and the old value
 * must remain answerable afterwards.
 *
 * <p>The write operations here maintain one invariant, which the resolver depends on and the
 * tests assert directly: <b>at any instant of transaction time, the versions the system
 * believes form a contiguous, non-overlapping timeline.</b> Every write is expressed as
 * "supersede what we believed, then record what we believe now" — never as an edit.
 */
@Entity
@Table(name = "policy")
public class Policy {

    @Id
    private UUID id;

    @Column(name = "policy_number", nullable = false, unique = true, length = 32)
    private String policyNumber;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Optimistic lock. Two endorsements racing on the same policy would each read the current
     * chain, each compute a correct-looking new chain from it, and the second commit would
     * leave a timeline with a hole or an overlap in it. The version check turns that into a
     * 409 for the loser instead.
     *
     * <p>Note that appending to {@link #versions} does not by itself dirty this row, so the
     * application layer takes {@code OPTIMISTIC_FORCE_INCREMENT} when it loads a policy for
     * writing. Relying on the default would leave the lock silently doing nothing.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PolicyVersion> versions = new ArrayList<>();

    protected Policy() {
        // for JPA
    }

    private Policy(String policyNumber, String productCode, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.policyNumber = requireText(policyNumber, "policyNumber").toUpperCase(Locale.ROOT);
        this.productCode = requireText(productCode, "productCode").toUpperCase(Locale.ROOT);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.versions = new ArrayList<>();
    }

    /**
     * Binds a new policy, creating version 1.
     *
     * @param expiryDate exclusive; {@code null} for a policy with no scheduled expiry
     */
    public static Policy bind(
            String policyNumber,
            String productCode,
            LocalDate inceptionDate,
            LocalDate expiryDate,
            Instant recordedAt,
            String insuredName,
            String territory,
            Collection<Coverage> coverages) {
        Policy policy = new Policy(policyNumber, productCode, recordedAt);
        if (coverages == null || coverages.isEmpty()) {
            throw new IllegalArgumentException("A bound policy must have at least one coverage line");
        }
        policy.versions.add(
                PolicyVersion.bind(policy, inceptionDate, expiryDate, recordedAt, insuredName, territory, coverages));
        return policy;
    }

    /**
     * Records a mid-term change effective from {@code effectiveFrom}, which may be in the past.
     *
     * <p>The new terms occupy the period from their effective date up to wherever the version
     * they displace ended — so a backdated endorsement slots into the timeline without erasing
     * later endorsements that are already on record. An endorsement effective 1 April, keyed in
     * after an endorsement effective 1 June is already recorded, covers April and May only; the
     * June terms still take over in June. That is how a policy administration system behaves,
     * and getting it wrong silently reverses a change the underwriter already made.
     *
     * @throws PolicyNotInForceException if the policy was not in force on {@code effectiveFrom}
     */
    public void endorse(
            LocalDate effectiveFrom,
            Instant recordedAt,
            String insuredName,
            String territory,
            String changeReason,
            Collection<Coverage> coverages) {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (coverages == null || coverages.isEmpty()) {
            throw new IllegalArgumentException("An endorsement must state at least one coverage line");
        }

        PolicyVersion displaced = requireInForceOn(effectiveFrom, recordedAt);
        int nextVersion = nextVersionNumber();

        // Stop believing the displaced version. Its row survives untouched, so a query "as we
        // believed before now" still returns it.
        displaced.supersede(recordedAt);

        // The endorsement lands mid-period: re-record the fragment before it that still holds.
        if (displaced.effectiveFrom().isBefore(effectiveFrom)) {
            versions.add(displaced.splitEndingAt(nextVersion++, effectiveFrom, recordedAt));
        }

        versions.add(PolicyVersion.endorse(
                this,
                nextVersion,
                effectiveFrom,
                displaced.effectiveTo(),
                recordedAt,
                insuredName,
                territory,
                changeReason,
                coverages));
    }

    /**
     * Cancels the policy from {@code effectiveFrom} onward.
     *
     * <p>Unlike an endorsement this is terminal: every version valid on or after the
     * cancellation date is superseded, because there is nothing left for later terms to apply
     * to. A backdated cancellation therefore does remove already-recorded later endorsements
     * from the current timeline — while leaving their rows readable, so the fact that they were
     * once believed is not lost.
     *
     * @throws PolicyNotInForceException if the policy was not in force on {@code effectiveFrom}
     */
    public void cancel(LocalDate effectiveFrom, Instant recordedAt, String changeReason) {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");

        PolicyVersion displaced = requireInForceOn(effectiveFrom, recordedAt);
        int nextVersion = nextVersionNumber();

        // Everything whose validity reaches the cancellation date or beyond stops being believed.
        List<PolicyVersion> affected = PolicyVersionResolver.currentChain(versions).stream()
                .filter(v -> v.effectiveTo() == null || v.effectiveTo().isAfter(effectiveFrom))
                .toList();

        for (PolicyVersion version : affected) {
            version.supersede(recordedAt);
            if (version.effectiveFrom().isBefore(effectiveFrom)) {
                versions.add(version.splitEndingAt(nextVersion++, effectiveFrom, recordedAt));
            }
        }

        versions.add(PolicyVersion.cancel(
                this,
                nextVersion,
                effectiveFrom,
                recordedAt,
                displaced.insuredName(),
                displaced.territory(),
                changeReason == null ? "Policy cancelled" : changeReason));
    }

    private PolicyVersion requireInForceOn(LocalDate date, Instant asAt) {
        PolicyVersion version = PolicyVersionResolver.resolve(versions, date, asAt)
                .orElseThrow(() -> new PolicyNotInForceException(policyNumber, date, "no version was in force"));
        if (!version.isInForce()) {
            throw new PolicyNotInForceException(policyNumber, date, "the policy was cancelled");
        }
        return version;
    }

    private int nextVersionNumber() {
        return versions.stream().mapToInt(PolicyVersion::versionNumber).max().orElse(0) + 1;
    }

    /** The version in force on {@code asOf}, as understood at {@code asAt}. */
    public Optional<PolicyVersion> versionAsOf(LocalDate asOf, Instant asAt) {
        return PolicyVersionResolver.resolve(versions, asOf, asAt);
    }

    /** The timeline the system currently believes, in effective-date order. */
    public List<PolicyVersion> currentChain() {
        return PolicyVersionResolver.currentChain(versions);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public UUID id() {
        return id;
    }

    public String policyNumber() {
        return policyNumber;
    }

    public String productCode() {
        return productCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long lockVersion() {
        return lockVersion;
    }

    /** Every version ever recorded, superseded ones included. */
    public List<PolicyVersion> allVersions() {
        return List.copyOf(versions);
    }

    @Override
    public String toString() {
        return "Policy[" + policyNumber + " " + productCode + " versions=" + versions.size() + "]";
    }
}
