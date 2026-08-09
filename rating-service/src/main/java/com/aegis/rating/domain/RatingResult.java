package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import java.util.List;

/**
 * A premium and the arithmetic that produced it.
 *
 * @param totalPremium always equal to the subtotal on the last worksheet line
 * @param worksheet every step, in application order, never empty
 * @param rateTable the exact published table used, so the premium is traceable to it
 */
public record RatingResult(Money totalPremium, List<WorksheetLine> worksheet, RateTable rateTable) {

    public RatingResult {
        worksheet = List.copyOf(worksheet);
    }

    /** The base premium before any multiplicative factor — the sum of the base-rate lines. */
    public Money basePremium() {
        return worksheet.stream()
                .filter(line -> line.type() == WorksheetStepType.BASE_RATE)
                .reduce((first, second) -> second)
                .map(WorksheetLine::subtotal)
                .orElseThrow(() -> new IllegalStateException("Worksheet has no base-rate lines"));
    }

    /** The worksheet as text, one line per step. What an underwriter actually reads. */
    public String renderWorksheet() {
        StringBuilder out =
                new StringBuilder("Rate table: ").append(rateTable.describe()).append('\n');
        worksheet.forEach(line -> out.append(line.render()).append('\n'));
        return out.append("TOTAL PREMIUM: ").append(totalPremium).toString();
    }
}
