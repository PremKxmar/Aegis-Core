package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One line of the arithmetic that produced a premium.
 *
 * @param stepNumber 1-based, and the order the steps were applied in
 * @param type which rule this line represents
 * @param description human-readable, e.g. {@code "Territory factor for TX-DALLAS"}
 * @param inputValue what went in, as displayed text: {@code "50000.00 USD limit"}, {@code "3 years"}
 * @param factorValue the multiplier applied, or {@code null} on additive base-rate lines
 * @param subtotal the running total after this step
 */
public record WorksheetLine(
        int stepNumber,
        WorksheetStepType type,
        String description,
        String inputValue,
        BigDecimal factorValue,
        Money subtotal) {

    /**
     * The scale every factor is normalised to, matching {@code NUMERIC(19,6)} in
     * {@code quote_worksheet_line.factor_value}.
     *
     * <p>Without this, a factor the engine produces in memory as {@code BigDecimal.ONE} (scale 0)
     * comes back from the database as {@code 1.000000} (scale 6), and a stored worksheet no
     * longer equals a freshly computed one — {@code BigDecimal.equals} is scale-sensitive. The
     * promise this service makes is that the same input reproduces the same output; a worksheet
     * that changes shape simply by being saved and reloaded breaks it.
     */
    public static final int FACTOR_SCALE = 6;

    public WorksheetLine {
        if (factorValue != null) {
            factorValue = factorValue.setScale(FACTOR_SCALE, RoundingMode.HALF_EVEN);
        }
    }

    /** e.g. {@code "3. Territory factor for TX-DALLAS  x1.15  -> 1046.50 USD"} */
    public String render() {
        String factor = factorValue == null
                ? ""
                : "  x" + factorValue.stripTrailingZeros().toPlainString();
        return stepNumber + ". " + description + " [" + inputValue + "]" + factor + "  -> " + subtotal;
    }
}
