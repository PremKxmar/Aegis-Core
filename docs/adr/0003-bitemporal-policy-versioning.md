# ADR 0003 — Bitemporal policy versioning over in-place updates and soft deletes

- **Status:** Accepted
- **Date:** 2026-08-10
- **Milestone:** 2

## Context

A claim arrives on 3 August reporting a collision that happened on 12 March. Between March and
August the policy was endorsed twice, and one of those endorsements was itself backdated —
keyed in during July, effective from April.

Three different questions have to be answerable, and they have three different answers:

1. What are the policy's terms **now**?
2. What were the terms **on 12 March** (so this claim can be settled correctly)?
3. What did we **believe on 1 June** the terms on 12 March were (so a decision made on 1 June
   can be explained, or defended)?

A model that stores "the current state of the policy" answers only the first. A model that
stores effective-dated versions answers the first two. Only recording both time dimensions
answers all three — and the third is the one an auditor, a regulator or a lawsuit asks about.

## Decision

A policy is an **append-only chain of versions**, each carrying two independent half-open
periods:

| Dimension | Columns | Meaning |
|---|---|---|
| Valid time | `effective_from`, `effective_to` | when the terms applied in the real world |
| Transaction time | `recorded_at`, `superseded_at` | when this system believed them |

Resolution filters on both at once:

```sql
WHERE effective_from <= :asOf  AND (effective_to  IS NULL OR effective_to  > :asOf)
  AND recorded_at    <= :asAt  AND (superseded_at IS NULL OR superseded_at > :asAt)
```

Three supporting decisions fall out of this and are worth stating explicitly.

**Both periods are half-open** — lower bound inclusive, upper bound exclusive. With inclusive
upper bounds, a version ending 31 May and the next starting 1 June must agree about which one
owns the boundary, and every boundary is a chance to be off by one day — on a claim whose loss
date falls exactly there. Half-open intervals make exactly one version match any date by
construction, not by care.

**An endorsement inherits the end date of the version it displaces.** A backdated endorsement
effective 1 April, recorded after an endorsement effective 1 June is already on file, covers
April and May only; the June terms still take over in June. Making the new version run to
infinity instead would silently reverse a change the underwriter had already made.

**Rows are never edited, with one deliberate exception.** Terms, dates and coverage lines on a
recorded row are immutable. The single mutable field is `superseded_at`, and setting it does not
change what the row says — it records that the system stopped believing it at an instant. This
is the same mechanism SQL:2011 system-versioned tables use. Without it, transaction time cannot
be closed and question 3 becomes unanswerable.

## Alternatives rejected

**Update the policy row in place.** The default, and it destroys the information the business
runs on. After a May endorsement there is simply no record of what the March terms were, so a
March loss is settled against August's limit — overpaying or wrongly denying, with no trace.

**Soft delete (`is_deleted`, or `is_current` flags).** Keeps the old rows, so it looks like it
solves the problem. It does not: a flag records *that* a row is no longer current but not *when*
it stopped being current, so "what did we believe on 1 June" is unanswerable. It also has no
place to put valid time, so a backdated endorsement is inexpressible — the new row is either
current or not, with no way to say it applies to a period that has already passed.

**Valid time only (effective-dated versions, no transaction time).** This is where most policy
systems stop, and it answers questions 1 and 2 correctly. Rejected because backdated changes
make it lie about the past: once a July-recorded, April-effective endorsement is inserted, the
system can no longer reproduce the answer it gave in June, and a claim denied in June looks —
from the data — like it should have been paid. That is precisely the reconstruction an auditor
asks for.

**An event-sourced policy aggregate.** Genuinely powerful, and a reasonable alternative. Every
change is an event and any past state is a replay. Rejected for three reasons: the coverage
query is on the hot path of every claim, and replaying an event stream per lookup is far worse
than an indexed range query; it forces every reader — including other services — to understand
the event model rather than a row; and it makes the temporal semantics implicit in replay logic
rather than visible in two pairs of columns. The bitemporal table *is* the projection an
event-sourced system would have to build anyway.

**Full SQL:2011 system-versioned tables (`PERIOD FOR SYSTEM_TIME`).** The standardised version
of exactly this. Not used because PostgreSQL does not implement it natively, and the extensions
that approximate it version *rows*, giving transaction time only — valid time would still have
to be modelled by hand.

## Consequences

**Accepted costs.**

- Writes are more expensive. An endorsement in the middle of a version's validity writes three
  rows: the superseded original stays, a `SPLIT` row re-records the surviving earlier fragment,
  and the endorsement itself is appended. The test suite asserts exactly this row count.
- The table only grows. Retention is a policy decision, not a technical one, and deleting from
  it deletes the ability to explain a past decision.
- Every read has to say *which* question it is asking. `asOf` defaults to today and `asAt` to
  now, so the common case stays simple, but the parameters are always there.
- Two implementations of the resolution rule — Java for exhaustive unit testing, SQL for an
  indexable query — with a parity test that walks a 60-point grid asserting they agree.

**Gained.**

- A claim is settled against the terms in force on its loss date, always.
- Any past decision can be reproduced exactly, including one made before a backdated
  endorsement existed.
- Nothing is ever lost. `GET /policies/{n}/versions` is a complete audit trail.

**Invariant the write path maintains, asserted directly in tests:** at any instant of
transaction time, the versions the system believes tile their span contiguously with no gaps and
no overlaps. `PolicyVersionResolver` throws `AmbiguousPolicyHistoryException` rather than
picking one if that is ever violated — an integrity bug must not degrade into a plausible but
wrong coverage answer.

## Verification

- `PolicyTemporalResolutionTest` — 30 cases in a written table: half-open boundaries, forward
  and backdated endorsements, same-day endorsement, cancellation, backdated cancellation, and
  operations outside the policy period.
- `PolicyVersionResolverTest` — boundary and ambiguity behaviour of the resolver itself.
- `PolicyResolutionParityIT` — the Java and SQL resolvers compared at 60 grid points over a
  history containing a forward-dated endorsement, a backdated one and a cancellation.
- `PolicyPersistenceIT` — asserts at the SQL level that an endorsement leaves the original row's
  `effective_from` and `effective_to` byte-for-byte unchanged and only sets `superseded_at`.
