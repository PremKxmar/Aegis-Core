package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A published set of rates, valid over a period of time.
 *
 * <p>Effective-dated for the same reason policies are: a quote issued in March must still be
 * reproducible in August. Rating always selects the table in force on the risk's effective date,
 * never "the latest one", so a re-rate of a March risk after a rate rise still produces March's
 * premium.
 *
 * <p>Nothing here is mutable. A rate change is a new table version with a new effective period,
 * not an edit — every quote that referenced the old one must keep referencing exactly what it
 * was rated against.
 */
@Entity
@Table(name = "rate_table")
public class RateTable {

    @Id
    private UUID id;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "table_version", nullable = false)
    private int tableVersion;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Exclusive. {@code null} means this is the current table with no successor yet. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "multi_policy_discount_factor", nullable = false, precision = 9, scale = 6)
    private BigDecimal multiPolicyDiscountFactor;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "minimum_premium_amount", nullable = false))
    @AttributeOverride(
            name = "currency",
            column = @Column(name = "minimum_premium_currency", nullable = false, length = 3))
    private Money minimumPremium;

    @OneToMany(mappedBy = "rateTable", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<RateBase> baseRates = new ArrayList<>();

    @OneToMany(mappedBy = "rateTable", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<RateTerritory> territoryFactors = new ArrayList<>();

    @OneToMany(mappedBy = "rateTable", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<RateAgeBand> ageBands = new ArrayList<>();

    @OneToMany(mappedBy = "rateTable", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<RateClaimsBand> claimsBands = new ArrayList<>();

    protected RateTable() {
        // for JPA
    }

    /*
     * Package-private construction. In production rate tables arrive from Flyway migrations —
     * published rates are reviewed and filed reference data, not something the application
     * creates — so nothing outside this package has any business building one. Tests construct
     * them in memory through this, which is what lets the engine be exercised against dozens of
     * generated tables without a database.
     */
    RateTable(
            String productCode,
            int tableVersion,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant publishedAt,
            BigDecimal multiPolicyDiscountFactor,
            Money minimumPremium) {
        this.id = UUID.randomUUID();
        this.productCode = productCode;
        this.tableVersion = tableVersion;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.publishedAt = publishedAt;
        this.multiPolicyDiscountFactor = multiPolicyDiscountFactor;
        this.minimumPremium = minimumPremium;
    }

    RateTable withBaseRate(String coverageCode, BigDecimal ratePerThousand) {
        baseRates.add(new RateBase(this, coverageCode.toUpperCase(Locale.ROOT), ratePerThousand));
        return this;
    }

    RateTable withTerritory(String territory, BigDecimal factor) {
        territoryFactors.add(new RateTerritory(this, territory.toUpperCase(Locale.ROOT), factor));
        return this;
    }

    RateTable withAgeBand(int minAgeYears, Integer maxAgeYears, BigDecimal factor) {
        ageBands.add(new RateAgeBand(this, minAgeYears, maxAgeYears, factor));
        return this;
    }

    RateTable withClaimsBand(int minClaims, Integer maxClaims, BigDecimal factor) {
        claimsBands.add(new RateClaimsBand(this, minClaims, maxClaims, factor));
        return this;
    }

    /** Whether these rates applied on the given date. Half-open: {@code from <= date < to}. */
    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
    }

    /**
     * The base rate per 1,000 of limit for a coverage.
     *
     * @throws UnratableRiskException if this table has no rate for that coverage. Defaulting to
     *     zero would quietly hand out free cover; defaulting to some other coverage's rate would
     *     be worse.
     */
    public BigDecimal ratePerThousand(String coverageCode) {
        String code = normalise(coverageCode);
        return baseRates.stream()
                .filter(r -> r.coverageCode().equals(code))
                .map(RateBase::ratePerThousand)
                .findFirst()
                .orElseThrow(() -> new UnratableRiskException(
                        "Rate table " + describe() + " has no base rate for coverage '" + code + "'"));
    }

    /** @throws UnratableRiskException if the territory is not rated by this table */
    public BigDecimal territoryFactor(String territory) {
        String code = normalise(territory);
        return territoryFactors.stream()
                .filter(t -> t.territory().equals(code))
                .map(RateTerritory::factor)
                .findFirst()
                .orElseThrow(() -> new UnratableRiskException(
                        "Rate table " + describe() + " does not rate territory '" + code + "'"));
    }

    /** @throws UnratableRiskException if no band covers that age, or more than one does */
    public BigDecimal assetAgeFactor(int ageYears) {
        List<RateAgeBand> matches =
                ageBands.stream().filter(b -> b.covers(ageYears)).toList();
        return single(matches, RateAgeBand::factor, "age band", ageYears + " years", matches::toString);
    }

    /** @throws UnratableRiskException if no band covers that claim count, or more than one does */
    public BigDecimal claimsHistoryFactor(int priorClaims) {
        List<RateClaimsBand> matches =
                claimsBands.stream().filter(b -> b.covers(priorClaims)).toList();
        return single(matches, RateClaimsBand::factor, "claims band", priorClaims + " prior claims", matches::toString);
    }

    /**
     * Resolves exactly one banded factor.
     *
     * <p>Overlapping bands are a data error, and the honest response is to refuse to rate rather
     * than to pick one. A premium produced from an arbitrary choice between two valid-looking
     * factors is wrong in a way nobody downstream can detect.
     */
    private <T> BigDecimal single(
            List<T> matches,
            java.util.function.Function<T, BigDecimal> factor,
            String bandKind,
            String input,
            java.util.function.Supplier<String> describeMatches) {
        if (matches.isEmpty()) {
            throw new UnratableRiskException("Rate table " + describe() + " has no " + bandKind + " covering " + input);
        }
        if (matches.size() > 1) {
            throw new UnratableRiskException("Rate table " + describe() + " has " + matches.size() + " overlapping "
                    + bandKind + "s covering " + input + ": " + describeMatches.get());
        }
        return factor.apply(matches.getFirst());
    }

    private static String normalise(String code) {
        if (code == null || code.isBlank()) {
            throw new UnratableRiskException("Code must not be blank");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** e.g. {@code "PERSONAL_AUTO v2"} — used in every error message so failures name the table. */
    public String describe() {
        return productCode + " v" + tableVersion;
    }

    public UUID id() {
        return id;
    }

    public String productCode() {
        return productCode;
    }

    public int tableVersion() {
        return tableVersion;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public BigDecimal multiPolicyDiscountFactor() {
        return multiPolicyDiscountFactor;
    }

    public Money minimumPremium() {
        return minimumPremium;
    }

    public List<RateBase> baseRates() {
        return List.copyOf(baseRates);
    }

    public List<RateTerritory> territoryFactors() {
        return List.copyOf(territoryFactors);
    }

    public List<RateAgeBand> ageBands() {
        return List.copyOf(ageBands);
    }

    public List<RateClaimsBand> claimsBands() {
        return List.copyOf(claimsBands);
    }

    @Override
    public String toString() {
        return "RateTable[" + describe() + " effective=[" + effectiveFrom + ","
                + (effectiveTo == null ? "∞" : effectiveTo) + ")]";
    }
}
