# ADR 0004 — A pure rating engine with stored worksheets

- **Status:** Accepted
- **Date:** 2026-08-10
- **Milestone:** 3

## Context

Two questions get asked about every premium, sometimes years later:

1. Why is this 1,847.50?
2. Would you get the same number if you rated it again?

Most rating code answers neither. It reads configuration at call time, uses the current date
somewhere in the middle, and returns a single number. When a customer disputes a premium, the
only available answer is to run it again — which produces today's answer, not the one that was
given.

## Decision

**The engine is a pure static function** of `(rateTable, input)`. No clock, no repository, no
randomness, no ambient state. The effective date is a parameter, not `LocalDate.now()`.

**Rate tables are versioned and effective-dated**, exactly like policy versions, and selected by
the risk's effective date rather than by recency. A March risk re-rated in December still selects
March's table.

**Rounding happens exactly once.** Intermediate arithmetic runs at 16 significant digits
(`MathContext(16, HALF_EVEN)`); the result becomes `Money` only at the end.

**The order of application is fixed and explicit**: base rates summed, then territory, asset age,
claims history, multi-policy discount, then the minimum premium floor last.

**Every quote stores its worksheet** — the ordered factors, each with its input, multiplier and
running subtotal — rather than recomputing it.

## Alternatives rejected

**Recompute the worksheet on demand.** Cheaper to store and always consistent with the code.
Rejected because it answers the wrong question: it tells you what the risk would cost under
today's rules, not why this customer was charged this. Those diverge the moment rates change,
which is exactly when someone asks.

**Round to cents at each step.** Feels safer and matches how people do it by hand. It makes the
result depend on the order factors happen to be applied in, so a refactor that reorders two
commutative multiplications silently changes premiums by a cent, and nothing fails.

**Apply the minimum premium floor before discounts.** Simpler to express. It lets a discount pull
the final premium below the filed minimum, which is a regulatory problem rather than a rounding
one. The property test `premiumIsNeverBelowTheMinimumPremiumFloor` pins the ordering down.

**A rules engine (Drools or similar).** Genuinely how some carriers do this, and it makes rate
changes deployable without a build. Rejected here because it moves the logic into a language that
cannot be unit-tested with jqwik, property-checked, or read by an interviewer — and the whole
value of this component is that its guarantees are checkable.

**Charging a flat base rate per coverage rather than per 1,000 of limit.** Simpler, and wrong:
premium would not respond to the limit at all, so a customer could raise their coverage for free.
Rating per unit of exposure is what makes `premiumIsMonotonicallyNonDecreasingInCoverageLimit`
a meaningful property rather than a tautology.

## Consequences

- Rate changes require a migration, not a config edit. That is the intended friction: published
  rates are reviewed and filed, and a premium can now be traced to a commit.
- The worksheet is written for every quote, so the quote tables grow faster than a
  premium-only design would. Cheap next to the cost of not being able to explain a number.
- Factor values are normalised to scale 6 to match `NUMERIC(19,6)`. Without it a factor computed
  in memory as `BigDecimal.ONE` comes back from the database as `1.000000`, and a stored
  worksheet stops equalling a freshly computed one — `BigDecimal.equals` is scale-sensitive.
  This was found by the round-trip test, not by inspection.
- Coverages are sorted by code before rating, so two quotes for the same risk that differ only in
  the order the caller listed them are byte-identical.

## Verification

- `RatingEngineTest` — worked examples with the arithmetic written out, so expected numbers can
  be checked by hand rather than copied from a previous run.
- `RatingEnginePropertiesTest` — 6 jqwik properties at 1,000 generated cases each: monotonic in
  coverage limit, never below the floor, worksheet reconciles to the total, rating twice is
  identical, a discount never increases the premium, premium is never negative.
- `RatingApiIT` — the same risk rated for 2025 and 2026 selects different filed tables and
  produces different premiums; a stored worksheet is compared field by field with the one issued.
