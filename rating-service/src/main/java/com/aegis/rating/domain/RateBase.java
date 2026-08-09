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
 * The base rate for one coverage, expressed per 1,000 of limit.
 *
 * <p>Rating per unit of exposure rather than as a flat charge is what makes the premium respond
 * to the limit at all, and it is what the monotonicity property test pins down: raising a limit
 * can never lower a premium.
 */
@Entity
@Table(name = "rate_base")
public class RateBase {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(name = "coverage_code", nullable = false, length = 32)
    private String coverageCode;

    @Column(name = "rate_per_thousand", nullable = false, precision = 19, scale = 6)
    private BigDecimal ratePerThousand;

    protected RateBase() {
        // for JPA
    }

    RateBase(RateTable rateTable, String coverageCode, BigDecimal ratePerThousand) {
        this.id = UUID.randomUUID();
        this.rateTable = rateTable;
        this.coverageCode = coverageCode;
        this.ratePerThousand = ratePerThousand;
    }

    public String coverageCode() {
        return coverageCode;
    }

    public BigDecimal ratePerThousand() {
        return ratePerThousand;
    }

    public UUID id() {
        return id;
    }
}
