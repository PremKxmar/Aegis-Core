package com.aegis.policy.domain;

import com.aegis.contracts.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One coverage line on a policy version: what is covered, up to what limit, above what
 * deductible, and what is carved out of it.
 *
 * <p>A coverage belongs to exactly one version and is never shared between versions. When an
 * endorsement raises a limit, the new version gets its own coverage rows; the old version's
 * rows stay untouched, still describing what was true before.
 */
@Entity
@Table(name = "coverage")
public class Coverage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false)
    private PolicyVersion policyVersion;

    @Column(name = "coverage_code", nullable = false, length = 32)
    private String coverageCode;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "limit_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "limit_currency", nullable = false, length = 3))
    private Money limit;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "deductible_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "deductible_currency", nullable = false, length = 3))
    private Money deductible;

    /**
     * Exclusion codes carved out of this coverage, e.g. {@code RACING}, {@code FLOOD}.
     *
     * <p>Eagerly fetched because a coverage is never useful without its exclusions: the one
     * question anybody asks of this object is "is this loss covered", and answering it always
     * requires the exclusions. Leaving them lazy would guarantee an N+1 on the hot path.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coverage_exclusion", joinColumns = @JoinColumn(name = "coverage_id"))
    @Column(name = "exclusion_code", nullable = false, length = 64)
    private Set<String> exclusions = new LinkedHashSet<>();

    protected Coverage() {
        // for JPA
    }

    public Coverage(String coverageCode, Money limit, Money deductible, Set<String> exclusions) {
        this.id = UUID.randomUUID();
        this.coverageCode = normalise(coverageCode);
        this.limit = requireNonNegative(limit, "limit");
        this.deductible = requireNonNegative(deductible, "deductible");
        this.exclusions = new LinkedHashSet<>();
        if (exclusions != null) {
            exclusions.stream().map(Coverage::normalise).forEach(this.exclusions::add);
        }
        if (!limit.currencyCode().equals(deductible.currencyCode())) {
            throw new IllegalArgumentException("Limit and deductible must share a currency, got " + limit.currencyCode()
                    + " and " + deductible.currencyCode());
        }
    }

    private static String normalise(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code must not be blank");
        }
        // Codes are compared exactly, all over the platform and across service boundaries, so
        // they are canonicalised once here rather than case-insensitively compared everywhere.
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static Money requireNonNegative(Money value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative, got " + value);
        }
        return value;
    }

    /** Copies this coverage onto another version. Used when a version has to be split. */
    Coverage copy() {
        return new Coverage(coverageCode, limit, deductible, exclusions);
    }

    void attachTo(PolicyVersion version) {
        this.policyVersion = version;
    }

    public boolean isExcluded(String exclusionCode) {
        return exclusionCode != null && exclusions.contains(normalise(exclusionCode));
    }

    public UUID id() {
        return id;
    }

    public String coverageCode() {
        return coverageCode;
    }

    public Money limit() {
        return limit;
    }

    public Money deductible() {
        return deductible;
    }

    public Set<String> exclusions() {
        return Set.copyOf(exclusions);
    }

    @Override
    public String toString() {
        return "Coverage[" + coverageCode + " limit=" + limit + " deductible=" + deductible + "]";
    }
}
