package com.aegis.policy.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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
 * One immutable slice of a policy's history, positioned in two independent time dimensions.
 *
 * <p><b>Valid time</b> — {@code [effectiveFrom, effectiveTo)} — is when these terms applied in
 * the real world. <b>Transaction time</b> — {@code [recordedAt, supersededAt)} — is when this
 * system believed them.
 *
 * <p>Both periods are half-open: the lower bound is inclusive, the upper bound exclusive, and
 * {@code null} means unbounded. Half-open intervals are not a stylistic preference. With
 * inclusive upper bounds, a version ending on 31 May and the next starting on 1 June must agree
 * about which day belongs to which, and every such boundary is an opportunity to be off by one
 * day — on a claim whose loss date falls exactly there. With exclusive upper bounds, adjacent
 * periods share their boundary value and exactly one of them matches any given date, by
 * construction.
 *
 * <p>Once written, the terms on a row never change. The single mutable field is
 * {@link #supersededAt}, and setting it does not alter what the row says — it records that the
 * system stopped believing it at a point in time. That is what makes "what did we believe on 1
 * June?" answerable at all: superseded rows are still there, still readable, just no longer
 * current.
 */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PolicyStatus status;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Exclusive. {@code null} means the terms run indefinitely. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Exclusive. {@code null} means this row is still what the system believes. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "insured_name", nullable = false, length = 200)
    private String insuredName;

    @Column(nullable = false, length = 32)
    private String territory;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @OneToMany(mappedBy = "policyVersion", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Coverage> coverages = new ArrayList<>();

    protected PolicyVersion() {
        // for JPA
    }

    // Widest constructor. Kept package-private and funnelled through the named factories below,
    // so that no caller can invent a combination of change type and status that has no meaning
    // (a BOUND version that is CANCELLED, say).
    private PolicyVersion(
            Policy policy,
            int versionNumber,
            ChangeType changeType,
            PolicyStatus status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant recordedAt,
            String insuredName,
            String territory,
            String changeReason,
            Collection<Coverage> coverages) {
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "Valid period must be non-empty: effectiveFrom=" + effectiveFrom + " effectiveTo=" + effectiveTo);
        }
        this.id = UUID.randomUUID();
        this.policy = Objects.requireNonNull(policy, "policy");
        this.versionNumber = versionNumber;
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.status = Objects.requireNonNull(status, "status");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.effectiveTo = effectiveTo;
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.insuredName = Objects.requireNonNull(insuredName, "insuredName");
        this.territory = Objects.requireNonNull(territory, "territory").trim().toUpperCase(Locale.ROOT);
        this.changeReason = changeReason;
        this.coverages = new ArrayList<>();
        if (coverages != null) {
            coverages.forEach(this::addCoverage);
        }
    }

    /** The first version of a policy, created at binding. */
    static PolicyVersion bind(
            Policy policy,
            LocalDate inceptionDate,
            LocalDate expiryDate,
            Instant recordedAt,
            String insuredName,
            String territory,
            Collection<Coverage> coverages) {
        return new PolicyVersion(
                policy,
                1,
                ChangeType.BOUND,
                PolicyStatus.IN_FORCE,
                inceptionDate,
                expiryDate,
                recordedAt,
                insuredName,
                territory,
                "Policy bound",
                coverages);
    }

    /** A mid-term change, effective from a date that may lie in the past. */
    static PolicyVersion endorse(
            Policy policy,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant recordedAt,
            String insuredName,
            String territory,
            String changeReason,
            Collection<Coverage> coverages) {
        return new PolicyVersion(
                policy,
                versionNumber,
                ChangeType.ENDORSED,
                PolicyStatus.IN_FORCE,
                effectiveFrom,
                effectiveTo,
                recordedAt,
                insuredName,
                territory,
                changeReason,
                coverages);
    }

    /**
     * A terminal version. It carries no coverage lines, so resolving a date on or after the
     * cancellation returns a version that answers "no coverage" rather than returning nothing
     * at all — the difference between "this policy was cancelled" and "no such policy", which
     * a claim handler very much needs to be told apart.
     */
    static PolicyVersion cancel(
            Policy policy,
            int versionNumber,
            LocalDate effectiveFrom,
            Instant recordedAt,
            String insuredName,
            String territory,
            String changeReason) {
        return new PolicyVersion(
                policy,
                versionNumber,
                ChangeType.CANCELLED,
                PolicyStatus.CANCELLED,
                effectiveFrom,
                null,
                recordedAt,
                insuredName,
                territory,
                changeReason,
                List.of());
    }

    /**
     * Re-records this version's terms over a shorter valid period.
     *
     * <p>Called when a backdated change lands inside this version's validity. The original row
     * is superseded rather than edited, and this copy takes over the fragment of time that
     * survives the change.
     */
    PolicyVersion splitEndingAt(int versionNumber, LocalDate newEffectiveTo, Instant recordedAt) {
        List<Coverage> copies = coverages.stream().map(Coverage::copy).toList();
        return new PolicyVersion(
                policy,
                versionNumber,
                ChangeType.SPLIT,
                status,
                effectiveFrom,
                newEffectiveTo,
                recordedAt,
                insuredName,
                territory,
                "Period closed by a later effective-dated change",
                copies);
    }

    private void addCoverage(Coverage coverage) {
        coverage.attachTo(this);
        coverages.add(coverage);
    }

    /**
     * Whether these terms applied on the given date.
     *
     * <p>{@code effectiveFrom <= date < effectiveTo}.
     */
    public boolean isValidOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
    }

    /**
     * Whether the system believed these terms at the given instant.
     *
     * <p>{@code recordedAt <= instant < supersededAt}.
     */
    public boolean wasBelievedAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(recordedAt) && (supersededAt == null || instant.isBefore(supersededAt));
    }

    /** Whether this row is what the system believes right now. */
    public boolean isCurrentBelief() {
        return supersededAt == null;
    }

    /** Records that the system stopped believing this row at {@code at}. The terms are untouched. */
    void supersede(Instant at) {
        if (supersededAt != null) {
            throw new IllegalStateException("Version " + id + " was already superseded at " + supersededAt);
        }
        if (at.isBefore(recordedAt)) {
            throw new IllegalArgumentException(
                    "Cannot supersede at " + at + ", before it was recorded at " + recordedAt);
        }
        this.supersededAt = at;
    }

    /** The coverage line for a code, if this version has one. */
    public Optional<Coverage> coverage(String coverageCode) {
        if (coverageCode == null) {
            return Optional.empty();
        }
        String normalised = coverageCode.trim().toUpperCase(Locale.ROOT);
        return coverages.stream()
                .filter(c -> c.coverageCode().equals(normalised))
                .findFirst();
    }

    public boolean isInForce() {
        return status == PolicyStatus.IN_FORCE;
    }

    public UUID id() {
        return id;
    }

    public Policy policy() {
        return policy;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public ChangeType changeType() {
        return changeType;
    }

    public PolicyStatus status() {
        return status;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public Instant supersededAt() {
        return supersededAt;
    }

    public String insuredName() {
        return insuredName;
    }

    public String territory() {
        return territory;
    }

    public String changeReason() {
        return changeReason;
    }

    public List<Coverage> coverages() {
        return List.copyOf(coverages);
    }

    @Override
    public String toString() {
        return "PolicyVersion[v" + versionNumber + " " + changeType + " valid=[" + effectiveFrom + ","
                + (effectiveTo == null ? "∞" : effectiveTo) + ") recorded=[" + recordedAt + ","
                + (supersededAt == null ? "∞" : supersededAt) + ")]";
    }
}
