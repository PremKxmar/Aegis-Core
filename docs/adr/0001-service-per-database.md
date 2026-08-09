# ADR 0001 — One database per service, with no shared tables

- **Status:** Accepted
- **Date:** 2026-08-10
- **Milestone:** 1

## Context

Aegis Core is three services — policy administration, rating and claims — that all describe
the same business. Claims needs a policy's coverage limits. Rating needs a policy's prior
claims history. The obvious, cheap thing to do is put all three in one database and let each
service `JOIN` whatever it needs.

That is the decision that quietly determines whether these are three services or one
distributed monolith, so it is worth making deliberately.

## Decision

Each service owns a **separate PostgreSQL database** with its own login role. No service holds
credentials for another's database. A service that needs another's data calls its HTTP API or
consumes its events; it never reads its tables.

Isolation is enforced by grants, not by convention
(`infra/postgres/init/01-create-service-databases.sql`):

```sql
REVOKE CONNECT ON DATABASE aegis_claims FROM PUBLIC;
GRANT  CONNECT ON DATABASE aegis_claims TO claims_svc;
```

`policy_svc` attempting to connect to `aegis_claims` is refused by PostgreSQL with
`permission denied for database`. There is no code review step to forget.

## Alternatives rejected

**One database, three schemas, with services trusted not to cross.** Cheaper to run and the
migration story is simpler. Rejected because the boundary is unenforceable: a single
`JOIN claims.claim ON policy.policy_id` written under deadline pressure creates a hidden
coupling that (a) makes it impossible to deploy or roll back the two services independently,
(b) makes a schema change in one service a breaking change in another with nothing to detect
it, and (c) silently defeats the point of separate services. The failure is not that the join
is slow — it is that nothing ever tells you it exists.

**Shared read-only replicas.** Same coupling, one layer removed. The reading service is still
bound to the writing service's physical schema.

**A single service.** Genuinely reasonable at this size, and worth saying out loud. Rejected
because the three domains have different change rates and different scaling profiles — rating
is CPU-bound and stateless, claims is write-heavy and stateful — and because independent
deployability is a core goal of the project.

## Consequences

**Accepted costs.**

- No cross-service transactions. Anything that must span services is eventually consistent,
  which is why the transactional outbox (ADR to follow in milestone 5) exists.
- No cross-service joins. Claims stores its own copy of the coverage terms it verified against,
  captured at the moment of verification — which turns out to be what an insurer actually
  wants, because a claim decision must be reproducible from what was known at the time.
- Three sets of migrations, three connection pools, three sets of credentials.

**Gained.**

- A schema change in policy-service cannot break claims-service at runtime; it can only break
  the published API contract, which contract tests catch at build time.
- Each service can be scaled, backed up, restored and rolled back on its own.
- The blast radius of a bad migration is one service.

## Verification

`make verify-stack` lists the three databases. The isolation itself is checked by connecting
as `policy_svc` to `aegis_claims` and asserting the connection is refused.
