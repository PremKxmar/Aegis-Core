# ADR 0002 — Money as a BigDecimal value object with HALF_EVEN rounding

- **Status:** Accepted
- **Date:** 2026-08-10
- **Milestone:** 1

## Context

Every number that matters in this platform is money: premiums, coverage limits, deductibles,
reserves, payments, recoveries. Two independent decisions have to be made — how the value is
represented, and how it is rounded — and both have well-known wrong answers that do not fail
loudly.

## Decision

A single immutable value object, `com.aegis.contracts.money.Money`, holding a `BigDecimal`
amount and an ISO-4217 currency code. It lives in the shared `contracts` module so that the
same definition is used on the wire, in the database and in the rating engine.

1. **`BigDecimal`, never `double` or `float`.** IEEE-754 binary floating point cannot
   represent 0.10 exactly. Ten dimes added as doubles come to 0.9999999999999999.
2. **Rounding is `HALF_EVEN`, declared once** as `Money.ROUNDING_MODE` and used by every
   operation on the class.
3. **Scale is the currency's minor unit**, not a hard-coded 2. USD and EUR get 2 places, JPY
   gets 0, KWD gets 3.
4. **Currency is part of the value.** `plus`, `minus`, `min`, `max` and `compareTo` all throw
   on a currency mismatch rather than returning a meaningless answer.
5. **On the wire the amount is a JSON string**, not a JSON number.

## Alternatives rejected

**`double`.** Fast and ergonomic, and wrong. The errors are small, silent, and accumulate in
exactly the place they are least acceptable — a ledger whose reserves and payments must
reconcile to the cent.

**Integer minor units (store cents as `long`).** A legitimate choice used by real payment
systems, and immune to representation error. Rejected because rating multiplies by fractional
factors (a 1.15 territory factor, a 0.9 multi-policy discount) dozens of times per quote, so
the code would be full of manual scaling back and forth, and each conversion is a place to put
the decimal point in the wrong spot. `BigDecimal` keeps the arithmetic looking like the
arithmetic on the rate sheet.

**`HALF_UP` rounding.** The intuitive default, and biased: it rounds every tie away from zero,
so across a large book of business the rounding error does not cancel — it accumulates in one
direction and shows up as a real, auditable discrepancy. `MoneyTest.halfUpWouldHaveBiasedTheSameSample`
demonstrates the drift on a four-value sample. HALF_EVEN is what accountants and regulators
expect.

**A hard-coded scale of 2.** Works until the first JPY or KWD amount. JPY has no minor unit, so
a scale of 2 invents subdivisions that do not exist; KWD has three, so a scale of 2 truncates
real money.

**JSON numbers on the wire.** Invites every JavaScript consumer to parse the amount into a
double, reintroducing the exact problem this ADR exists to prevent — at the system boundary,
where it is hardest to detect.

**A third-party money library (JSR-354 / Moneta, Joda-Money).** Reasonable in production. Not
used here because the surface actually needed is about a dozen methods, and an interviewer
reading this repository learns more from a Money class they can read in full than from a
dependency.

## Consequences

- Money is stored as `NUMERIC(19,4)` — a superset of every currency's minor unit, so a
  three-decimal currency is never truncated by the column. The in-memory value is normalised to
  the currency scale on both construction and read, because Hibernate writes fields directly
  and hands back the column's scale rather than the currency's.
- `equals` and `hashCode` compare normalised amounts. `BigDecimal.equals` is scale-sensitive
  and would call `10.00` and `10.0000` different values — meaning a Money loaded from the
  database would not equal the identical Money built in memory.
- Rating must do chained factor arithmetic on raw `BigDecimal` and convert to `Money` once, at
  the end. Rounding to cents after every factor makes the result depend on the order the
  factors were applied, which would break the reproducibility the rating engine promises.
- ISO-4217 pseudo-currencies (XAU gold, XDR) are rejected at construction: Java reports `-1`
  minor units for them and `setScale(-1)` would round every amount to the nearest ten.

## Verification

`contracts/src/test/java/com/aegis/contracts/money/MoneyTest.java` — 44 tests covering scale
normalisation per currency, the HALF_EVEN tie table including negatives, the HALF_UP bias
comparison, currency-mismatch rejection, cross-scale equality, and the JSON wire format.
