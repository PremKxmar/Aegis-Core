package com.aegis.contracts.money;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An immutable monetary amount in a single ISO-4217 currency.
 *
 * <p>Money is never a {@code double} anywhere in this platform. Binary floating point cannot
 * represent 0.10, so a premium built by adding ten dimes lands on 0.9999999999999999 and a
 * ledger that reconciles reserves against payments drifts by cents that nobody can explain.
 * Every amount here is a {@link BigDecimal} at a scale fixed by the currency.
 *
 * <p><strong>Rounding is declared exactly once</strong>, in {@link #ROUNDING_MODE}. HALF_EVEN
 * ("banker's rounding") is the choice because it has no bias: HALF_UP systematically rounds
 * ties away from zero, which over a large book of premiums or claim payments accumulates into
 * a real, auditable discrepancy. Insurance regulators and accountants expect HALF_EVEN.
 *
 * <p><strong>Scale.</strong> An instance is always normalised to its currency's minor unit
 * (2 for USD and EUR, 0 for JPY, 3 for KWD), so two amounts in the same currency always share
 * a scale. Rating arithmetic that genuinely needs more precision — chained factor
 * multiplication, for instance — should be done on raw {@code BigDecimal} at a higher scale
 * and converted to {@code Money} once, at the end. Rounding at every intermediate step is how
 * a rating engine stops being reproducible.
 *
 * <p>This class carries both Jackson and JPA annotations. That is a deliberate trade: a
 * separate persistence-side and wire-side representation of money would be purer, but it
 * would mean two definitions of the same invariant, and the invariant is the whole point.
 */
@Embeddable
// The wire format is opt-in, not whatever Jackson happens to discover. Without this, the
// isZero()/isPositive()/isNegative() predicates are auto-detected as bean properties and leak
// into every serialised payload as "zero", "positive" and "negative" fields — and any new
// predicate added later would silently change the published contract.
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public final class Money implements Comparable<Money>, Serializable {

    private static final long serialVersionUID = 1L;

    /** The single rounding mode used for every monetary operation in the platform. */
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /**
     * Stored at scale 4 rather than 2 so that the column is a superset of every currency's
     * minor unit — KWD and BHD have three decimal places, and a NUMERIC(19,2) column would
     * silently truncate them. The in-memory value is still normalised to the currency scale.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Required by JPA. Hibernate populates the fields reflectively. */
    protected Money() {
        // Left uninitialised on purpose: Hibernate assigns both fields immediately after
        // construction, and giving them defaults here would mask a mapping mistake.
    }

    private Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * Creates an amount, normalising it to the currency's minor unit with {@link #ROUNDING_MODE}.
     *
     * @param amount the value; rounded, not rejected, if it carries more precision than the
     *     currency supports
     * @param currencyCode an ISO-4217 alphabetic code, case-insensitive
     * @throws IllegalArgumentException if the currency code is not a known ISO-4217 code
     */
    @JsonCreator
    public static Money of(@JsonProperty("amount") BigDecimal amount, @JsonProperty("currency") String currencyCode) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        Currency resolved = resolveCurrency(currencyCode);
        return new Money(
                amount.setScale(resolved.getDefaultFractionDigits(), ROUNDING_MODE), resolved.getCurrencyCode());
    }

    /** Convenience factory for literals, e.g. {@code Money.of("1250.00", "USD")}. */
    public static Money of(String amount, String currencyCode) {
        Objects.requireNonNull(amount, "amount must not be null");
        return of(new BigDecimal(amount), currencyCode);
    }

    /** The zero amount in the given currency. */
    public static Money zero(String currencyCode) {
        return of(BigDecimal.ZERO, currencyCode);
    }

    private static Currency resolveCurrency(String currencyCode) {
        Currency resolved;
        try {
            // Uppercasing is a wire-format convenience — "usd" in a JSON payload means USD.
            // Locale.ROOT avoids the Turkish dotless-i trap that Locale-sensitive case
            // conversion introduces for codes containing 'i'.
            resolved = Currency.getInstance(currencyCode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid ISO-4217 currency code: '" + currencyCode + "'", e);
        }
        // ISO-4217 also assigns codes to things that are not spendable money: XAU (gold),
        // XDR (IMF drawing rights), XXX ("no currency"). Java reports -1 minor units for
        // those, and setScale(-1) would quietly round every amount to the nearest ten.
        if (resolved.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "'" + resolved.getCurrencyCode() + "' is a pseudo-currency with no minor unit");
        }
        return resolved;
    }

    /** The amount, normalised to the currency's minor unit. */
    @JsonProperty("amount")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal amount() {
        // Normalising on read as well as on construction matters because Hibernate writes the
        // field directly, bypassing the factory, and hands back whatever scale the database
        // column produced (4). Without this, an entity loaded from the database would not
        // equal an otherwise identical value built in memory.
        return amount.setScale(currency().getDefaultFractionDigits(), ROUNDING_MODE);
    }

    /** The ISO-4217 alphabetic code, e.g. {@code "USD"}. */
    @JsonProperty("currency")
    public String currencyCode() {
        return currency;
    }

    /** The currency as a {@link Currency}, for locale-aware formatting and minor-unit lookups. */
    public Currency currency() {
        return Currency.getInstance(currency);
    }

    /** @throws IllegalArgumentException if {@code other} is in a different currency */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return of(amount().add(other.amount()), currency);
    }

    /** @throws IllegalArgumentException if {@code other} is in a different currency */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return of(amount().subtract(other.amount()), currency);
    }

    /**
     * Multiplies by a dimensionless factor — a territory or vehicle-age rating factor, say.
     * The product is rounded back to the currency scale, so callers chaining several factors
     * should multiply the factors together first and apply the result once.
     */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        return of(amount().multiply(factor), currency);
    }

    /** Multiplies by a whole number, e.g. a count of exposure units. */
    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    /** The additive inverse. Used to express ledger reversals as first-class entries. */
    public Money negated() {
        return of(amount().negate(), currency);
    }

    /** The larger of the two amounts — used to apply a minimum premium floor. */
    public Money max(Money other) {
        requireSameCurrency(other);
        return compareTo(other) >= 0 ? this : other;
    }

    /** The smaller of the two amounts — used to cap a payment at the remaining coverage limit. */
    public Money min(Money other) {
        requireSameCurrency(other);
        return compareTo(other) <= 0 ? this : other;
    }

    public boolean isZero() {
        return amount().signum() == 0;
    }

    public boolean isPositive() {
        return amount().signum() > 0;
    }

    public boolean isNegative() {
        return amount().signum() < 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine amounts in different currencies: " + currency + " and " + other.currency);
        }
    }

    /**
     * Orders two amounts in the same currency.
     *
     * @throws IllegalArgumentException if the currencies differ. Returning an arbitrary order
     *     for mixed currencies would let a sort silently produce nonsense; failing loudly is
     *     the only honest answer to "is 10 USD more than 10 EUR".
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount().compareTo(other.amount());
    }

    /**
     * Value equality. Both operands are compared at their normalised scale, so {@code 10.00 USD}
     * loaded from a NUMERIC(19,4) column equals {@code 10.00 USD} built in memory —
     * {@link BigDecimal#equals} alone would call those two different because it is
     * scale-sensitive.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return currency.equals(other.currency) && amount().equals(other.amount());
    }

    @Override
    public int hashCode() {
        // Safe to hash the BigDecimal directly despite BigDecimal.hashCode being
        // scale-sensitive: amount() guarantees that two equal amounts in the same currency
        // always carry the same scale.
        return Objects.hash(amount(), currency);
    }

    /** e.g. {@code "1250.00 USD"} — amount first, so log lines sort sensibly. */
    @Override
    public String toString() {
        return amount().toPlainString() + " " + currency;
    }
}
