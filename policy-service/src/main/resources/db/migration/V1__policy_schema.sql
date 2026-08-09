-- Policy administration schema.
--
-- The central idea: a policy is not a row that changes. It is an append-only chain of
-- effective-dated versions, each of which records two independent time periods.
--
--   VALID TIME       [effective_from, effective_to)   when the terms applied in the real world
--   TRANSACTION TIME [recorded_at, superseded_at)     when this system believed them
--
-- Two periods rather than one is what makes a backdated endorsement expressible. An endorsement
-- keyed in on 20 July but effective from 1 April produces a row whose valid time starts in April
-- and whose transaction time starts in July. The system can then answer both:
--
--   "what was covered on 12 March?"                        -> filter on valid time
--   "what did we believe on 1 June was covered on 12 March?" -> filter on both
--
-- and the second question is the one that matters when a claim decision has to be defended, or
-- when a regulator asks why a claim was denied in June and paid in August.

CREATE TABLE policy (
    id               UUID         PRIMARY KEY,
    policy_number    VARCHAR(32)  NOT NULL UNIQUE,
    product_code     VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    -- Optimistic locking. Two concurrent endorsements against the same policy would each
    -- resolve the current version, compute a new chain, and the second write would silently
    -- overwrite the first's view of history. @Version turns that into a 409.
    lock_version     BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE policy IS 'Aggregate root. Holds identity only - all terms live on policy_version.';

CREATE TABLE policy_version (
    id                UUID         PRIMARY KEY,
    policy_id         UUID         NOT NULL REFERENCES policy (id),

    -- Monotonic per policy, for human reference ("endorsement 3"). Note this is NOT the
    -- ordering used for resolution: a backdated endorsement has a higher version number but an
    -- earlier effective date, so resolution orders by time, never by this.
    version_number    INTEGER      NOT NULL,
    change_type       VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,

    -- Valid time. effective_from is inclusive, effective_to is EXCLUSIVE and NULL means open.
    -- Exclusive upper bounds remove the whole class of off-by-one-day bugs that inclusive
    -- bounds create: a version ending the day another begins shares no day with it, and the
    -- two conditions never both match.
    effective_from    DATE         NOT NULL,
    effective_to      DATE,

    -- Transaction time, same half-open convention. superseded_at NULL means "still believed".
    recorded_at       TIMESTAMPTZ  NOT NULL,
    superseded_at     TIMESTAMPTZ,

    insured_name      VARCHAR(200) NOT NULL,
    territory         VARCHAR(32)  NOT NULL,
    -- Free text describing what this change was, e.g. 'Increased collision limit to 100,000'.
    change_reason     VARCHAR(500),

    CONSTRAINT policy_version_valid_period  CHECK (effective_to  IS NULL OR effective_to  > effective_from),
    CONSTRAINT policy_version_txn_period    CHECK (superseded_at IS NULL OR superseded_at >= recorded_at),
    -- SPLIT marks a row written only to close off an earlier version's valid-time period when a
    -- backdated change lands in the middle of it. Its terms are a copy; only the period differs.
    CONSTRAINT policy_version_change_type   CHECK (change_type IN ('BOUND', 'ENDORSED', 'CANCELLED', 'SPLIT')),
    CONSTRAINT policy_version_status        CHECK (status IN ('IN_FORCE', 'CANCELLED'))
);

-- The index that the as-of resolver actually uses: narrow by policy, then by belief, then by
-- validity. Partial on superseded_at IS NULL because the overwhelmingly common query is
-- "as things stand now", and that predicate is what makes the index small.
CREATE INDEX idx_policy_version_current
    ON policy_version (policy_id, effective_from DESC)
    WHERE superseded_at IS NULL;

-- Full bitemporal lookups ("as we believed on date B") cannot use the partial index.
CREATE INDEX idx_policy_version_bitemporal
    ON policy_version (policy_id, recorded_at, superseded_at);

CREATE TABLE coverage (
    id                 UUID          PRIMARY KEY,
    policy_version_id  UUID          NOT NULL REFERENCES policy_version (id) ON DELETE CASCADE,
    coverage_code      VARCHAR(32)   NOT NULL,

    -- NUMERIC(19,4) rather than (19,2): a superset of every ISO-4217 minor unit, so a
    -- three-decimal currency such as KWD is never truncated by the column. See ADR 0002.
    limit_amount       NUMERIC(19,4) NOT NULL,
    limit_currency     VARCHAR(3)    NOT NULL,
    deductible_amount  NUMERIC(19,4) NOT NULL,
    deductible_currency VARCHAR(3)   NOT NULL,

    CONSTRAINT coverage_limit_non_negative      CHECK (limit_amount >= 0),
    CONSTRAINT coverage_deductible_non_negative CHECK (deductible_amount >= 0),
    -- A coverage line cannot mix currencies with itself.
    CONSTRAINT coverage_single_currency         CHECK (limit_currency = deductible_currency),
    -- One line per coverage code per version. Two COLLISION lines on one version would make
    -- "the limit for COLLISION" ambiguous, and the ambiguity would surface as an arbitrary
    -- claim payment.
    CONSTRAINT coverage_unique_per_version      UNIQUE (policy_version_id, coverage_code)
);

CREATE INDEX idx_coverage_policy_version ON coverage (policy_version_id);

CREATE TABLE coverage_exclusion (
    coverage_id     UUID         NOT NULL REFERENCES coverage (id) ON DELETE CASCADE,
    exclusion_code  VARCHAR(64)  NOT NULL,

    PRIMARY KEY (coverage_id, exclusion_code)
);
