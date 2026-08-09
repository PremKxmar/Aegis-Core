package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A premium that was quoted, together with the arithmetic that produced it.
 *
 * <p>The worksheet is stored, not recomputed on demand. Recomputing would answer "what would this
 * risk cost under today's rules", which is a different question from "why was this customer
 * charged this" — and the second is the one asked in a complaint, an audit or a lawsuit. Storing
 * it also means the explanation survives a rate table being superseded.
 */
@Entity
@Table(name = "quote")
public class Quote {

    @Id
    private UUID id;

    @Column(name = "quote_number", nullable = false, unique = true, length = 32)
    private String quoteNumber;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    /**
     * Denormalised from {@link #rateTable}. Redundant on purpose: the version is what a human
     * quotes when querying a premium, and reading it should not require joining to a table whose
     * row could in principle be archived.
     */
    @Column(name = "rate_table_version", nullable = false)
    private int rateTableVersion;

    @Column(nullable = false, length = 32)
    private String territory;

    @Column(name = "asset_age_years", nullable = false)
    private int assetAgeYears;

    @Column(name = "prior_claims_count", nullable = false)
    private int priorClaimsCount;

    @Column(name = "policies_held", nullable = false)
    private int policiesHeld;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_premium_amount", nullable = false))
    @AttributeOverride(
            name = "currency",
            column = @Column(name = "total_premium_currency", nullable = false, length = 3))
    private Money totalPremium;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<QuoteCoverage> coverages = new ArrayList<>();

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<QuoteWorksheetLine> worksheetLines = new ArrayList<>();

    protected Quote() {
        // for JPA
    }

    public static Quote from(String quoteNumber, RatingInput input, RatingResult result, Instant createdAt) {
        Quote quote = new Quote();
        quote.id = UUID.randomUUID();
        quote.quoteNumber = quoteNumber;
        quote.productCode = input.productCode();
        quote.effectiveDate = input.effectiveDate();
        quote.rateTable = result.rateTable();
        quote.rateTableVersion = result.rateTable().tableVersion();
        quote.territory = input.territory();
        quote.assetAgeYears = input.assetAgeYears();
        quote.priorClaimsCount = input.priorClaimsCount();
        quote.policiesHeld = input.policiesHeld();
        quote.totalPremium = result.totalPremium();
        quote.createdAt = createdAt;

        input.coverages().forEach(c -> quote.coverages.add(new QuoteCoverage(quote, c.coverageCode(), c.limit())));
        result.worksheet().forEach(line -> quote.worksheetLines.add(new QuoteWorksheetLine(quote, line)));
        return quote;
    }

    /** The worksheet in application order, which is the only order it means anything in. */
    public List<QuoteWorksheetLine> worksheet() {
        return worksheetLines.stream()
                .sorted(Comparator.comparingInt(QuoteWorksheetLine::stepNumber))
                .toList();
    }

    public UUID id() {
        return id;
    }

    public String quoteNumber() {
        return quoteNumber;
    }

    public String productCode() {
        return productCode;
    }

    public LocalDate effectiveDate() {
        return effectiveDate;
    }

    public RateTable rateTable() {
        return rateTable;
    }

    public int rateTableVersion() {
        return rateTableVersion;
    }

    public String territory() {
        return territory;
    }

    public int assetAgeYears() {
        return assetAgeYears;
    }

    public int priorClaimsCount() {
        return priorClaimsCount;
    }

    public int policiesHeld() {
        return policiesHeld;
    }

    public Money totalPremium() {
        return totalPremium;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<QuoteCoverage> coverages() {
        return List.copyOf(coverages);
    }
}
