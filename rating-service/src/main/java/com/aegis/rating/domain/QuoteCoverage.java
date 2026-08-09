package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A coverage that was rated, recorded with the limit it was rated at. */
@Entity
@Table(name = "quote_coverage")
public class QuoteCoverage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "coverage_code", nullable = false, length = 32)
    private String coverageCode;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "limit_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "limit_currency", nullable = false, length = 3))
    private Money limit;

    protected QuoteCoverage() {
        // for JPA
    }

    QuoteCoverage(Quote quote, String coverageCode, Money limit) {
        this.id = UUID.randomUUID();
        this.quote = quote;
        this.coverageCode = coverageCode;
        this.limit = limit;
    }

    public String coverageCode() {
        return coverageCode;
    }

    public Money limit() {
        return limit;
    }

    public UUID id() {
        return id;
    }
}
