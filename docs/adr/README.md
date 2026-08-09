# Architecture Decision Records

One file per decision that was expensive to make and would be expensive to reverse. Each
records the context, the decision, **the alternatives that were rejected and why**, and the
consequences accepted along with it.

An ADR is written in the milestone that makes the decision, not in advance — a record written
before the problem is understood is a guess with a number on it.

| ADR | Decision | Milestone |
|-----|----------|-----------|
| [0001](0001-service-per-database.md) | One database per service, with no shared tables | 1 |
| [0002](0002-money-representation.md) | Money as a BigDecimal value object with HALF_EVEN rounding | 1 |
| [0003](0003-bitemporal-policy-versioning.md) | Bitemporal policy versioning over in-place updates and soft deletes | 2 |
| [0004](0004-deterministic-rating-with-stored-worksheets.md) | A pure rating engine with stored worksheets | 3 |
| 0005 | Transactional outbox over dual writes | 5 (pending) |
