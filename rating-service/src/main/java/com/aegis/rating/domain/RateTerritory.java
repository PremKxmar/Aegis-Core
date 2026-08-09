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

/** The rating factor for one territory: how much more or less risk that geography carries. */
@Entity
@Table(name = "rate_territory")
public class RateTerritory {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(nullable = false, length = 32)
    private String territory;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal factor;

    protected RateTerritory() {
        // for JPA
    }

    RateTerritory(RateTable rateTable, String territory, BigDecimal factor) {
        this.id = UUID.randomUUID();
        this.rateTable = rateTable;
        this.territory = territory;
        this.factor = factor;
    }

    public String territory() {
        return territory;
    }

    public BigDecimal factor() {
        return factor;
    }

    public UUID id() {
        return id;
    }
}
