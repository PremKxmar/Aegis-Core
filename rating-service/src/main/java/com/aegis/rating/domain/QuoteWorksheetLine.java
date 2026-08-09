package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** One stored line of a quote's worksheet. The persisted form of {@link WorksheetLine}. */
@Entity
@Table(name = "quote_worksheet_line")
public class QuoteWorksheetLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 32)
    private WorksheetStepType stepType;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "input_value", nullable = false, length = 100)
    private String inputValue;

    /** Null on additive base-rate lines, which have no multiplier. */
    @Column(name = "factor_value", precision = 19, scale = 6)
    private BigDecimal factorValue;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "subtotal_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "subtotal_currency", nullable = false, length = 3))
    private Money subtotal;

    protected QuoteWorksheetLine() {
        // for JPA
    }

    QuoteWorksheetLine(Quote quote, WorksheetLine line) {
        this.id = UUID.randomUUID();
        this.quote = quote;
        this.stepNumber = line.stepNumber();
        this.stepType = line.type();
        this.description = line.description();
        this.inputValue = line.inputValue();
        this.factorValue = line.factorValue();
        this.subtotal = line.subtotal();
    }

    /** Back to the in-memory form, so stored and freshly-computed worksheets render identically. */
    public WorksheetLine toWorksheetLine() {
        return new WorksheetLine(stepNumber, stepType, description, inputValue, factorValue, subtotal);
    }

    public int stepNumber() {
        return stepNumber;
    }

    public WorksheetStepType stepType() {
        return stepType;
    }

    public String description() {
        return description;
    }

    public String inputValue() {
        return inputValue;
    }

    public BigDecimal factorValue() {
        return factorValue;
    }

    public Money subtotal() {
        return subtotal;
    }

    public UUID id() {
        return id;
    }
}
