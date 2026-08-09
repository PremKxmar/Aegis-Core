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

/** A surcharge factor for a band of prior at-fault claim counts. Half-open, like every band here. */
@Entity
@Table(name = "rate_claims_band")
public class RateClaimsBand {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(name = "min_claims", nullable = false)
    private int minClaims;

    /** Exclusive. {@code null} means the band is open-ended. */
    @Column(name = "max_claims")
    private Integer maxClaims;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal factor;

    protected RateClaimsBand() {
        // for JPA
    }

    RateClaimsBand(RateTable rateTable, int minClaims, Integer maxClaims, BigDecimal factor) {
        this.id = UUID.randomUUID();
        this.rateTable = rateTable;
        this.minClaims = minClaims;
        this.maxClaims = maxClaims;
        this.factor = factor;
    }

    public boolean covers(int claimCount) {
        return claimCount >= minClaims && (maxClaims == null || claimCount < maxClaims);
    }

    public BigDecimal factor() {
        return factor;
    }

    public int minClaims() {
        return minClaims;
    }

    public Integer maxClaims() {
        return maxClaims;
    }

    public UUID id() {
        return id;
    }

    @Override
    public String toString() {
        return "ClaimsBand[" + minClaims + ".." + (maxClaims == null ? "∞" : maxClaims) + ") x" + factor + "]";
    }
}
