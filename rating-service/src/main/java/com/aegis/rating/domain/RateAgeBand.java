package com.aegis.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A factor for a band of asset ages — the age of the vehicle, or of the roof on a property.
 *
 * <p>The band is half-open, {@code minAgeYears <= age < maxAgeYears}, for the same reason policy
 * versions are: two adjacent bands can never both claim the same age, so there is no boundary
 * where the applicable factor is a matter of opinion.
 */
@Entity
@Table(name = "rate_age_band")
public class RateAgeBand {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(name = "min_age_years", nullable = false)
    private int minAgeYears;

    /** Exclusive. {@code null} means the band is open-ended. */
    @Column(name = "max_age_years")
    private Integer maxAgeYears;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal factor;

    protected RateAgeBand() {
        // for JPA
    }

    RateAgeBand(RateTable rateTable, int minAgeYears, Integer maxAgeYears, BigDecimal factor) {
        this.id = UUID.randomUUID();
        this.rateTable = rateTable;
        this.minAgeYears = minAgeYears;
        this.maxAgeYears = maxAgeYears;
        this.factor = factor;
    }

    public boolean covers(int ageYears) {
        return ageYears >= minAgeYears && (maxAgeYears == null || ageYears < maxAgeYears);
    }

    public BigDecimal factor() {
        return factor;
    }

    public int minAgeYears() {
        return minAgeYears;
    }

    public Integer maxAgeYears() {
        return maxAgeYears;
    }

    public UUID id() {
        return id;
    }

    @Override
    public String toString() {
        return "AgeBand[" + minAgeYears + ".." + (maxAgeYears == null ? "∞" : maxAgeYears) + ") x" + factor + "]";
    }
}
