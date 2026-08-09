-- Rating schema.
--
-- Two ideas drive the shape of these tables.
--
-- 1. RATE TABLES ARE VERSIONED AND EFFECTIVE-DATED. A premium is not "what the rates say"; it is
--    "what the rates said on the date this risk was rated". Rates change, and a quote issued in
--    March must still be reproducible in August, byte for byte. So rate_table carries its own
--    effective period and nothing in it is ever edited after publication.
--
-- 2. EVERY QUOTE STORES ITS WORKSHEET. Not the premium alone - the ordered list of factors that
--    produced it, each with its input, its factor value and the running subtotal. An underwriter
--    asked "why is this 1,847.50?" needs the arithmetic, not a number. Recomputing it later is
--    not the same thing: it tells you what the rules say today, not what they said then.

CREATE TABLE rate_table (
    id                          UUID          PRIMARY KEY,
    product_code                VARCHAR(32)   NOT NULL,
    table_version               INTEGER       NOT NULL,

    -- Valid time, half-open, same convention as policy versions: effective_to is EXCLUSIVE.
    effective_from              DATE          NOT NULL,
    effective_to                DATE,

    published_at                TIMESTAMPTZ   NOT NULL,

    -- Table-wide parameters. Held here rather than in a generic key/value table so they are
    -- typed, constrained and impossible to misspell.
    multi_policy_discount_factor NUMERIC(9,6) NOT NULL,
    minimum_premium_amount      NUMERIC(19,4) NOT NULL,
    minimum_premium_currency    VARCHAR(3)    NOT NULL,

    CONSTRAINT rate_table_unique_version   UNIQUE (product_code, table_version),
    CONSTRAINT rate_table_valid_period     CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT rate_table_discount_range   CHECK (multi_policy_discount_factor > 0 AND multi_policy_discount_factor <= 1),
    CONSTRAINT rate_table_minimum_positive CHECK (minimum_premium_amount >= 0)
);

CREATE INDEX idx_rate_table_lookup ON rate_table (product_code, effective_from DESC);

-- Base rate per coverage, expressed per 1,000 of limit. Rating per unit of exposure rather than
-- as a flat charge is what makes premium respond to the limit at all - and it is the property
-- the monotonicity test pins down: raising a limit can never lower a premium.
CREATE TABLE rate_base (
    id                UUID          PRIMARY KEY,
    rate_table_id     UUID          NOT NULL REFERENCES rate_table (id) ON DELETE CASCADE,
    coverage_code     VARCHAR(32)   NOT NULL,
    rate_per_thousand NUMERIC(19,6) NOT NULL,

    CONSTRAINT rate_base_unique       UNIQUE (rate_table_id, coverage_code),
    CONSTRAINT rate_base_non_negative CHECK (rate_per_thousand >= 0)
);

CREATE TABLE rate_territory (
    id            UUID          PRIMARY KEY,
    rate_table_id UUID          NOT NULL REFERENCES rate_table (id) ON DELETE CASCADE,
    territory     VARCHAR(32)   NOT NULL,
    factor        NUMERIC(9,6)  NOT NULL,

    CONSTRAINT rate_territory_unique   UNIQUE (rate_table_id, territory),
    CONSTRAINT rate_territory_positive CHECK (factor > 0)
);

-- Age of the insured asset in whole years, as a band. Bands are half-open on the upper end
-- (min_age <= age < max_age) so adjacent bands cannot both match, and max_age NULL means open.
CREATE TABLE rate_age_band (
    id            UUID          PRIMARY KEY,
    rate_table_id UUID          NOT NULL REFERENCES rate_table (id) ON DELETE CASCADE,
    min_age_years INTEGER       NOT NULL,
    max_age_years INTEGER,
    factor        NUMERIC(9,6)  NOT NULL,

    CONSTRAINT rate_age_band_range    CHECK (max_age_years IS NULL OR max_age_years > min_age_years),
    CONSTRAINT rate_age_band_positive CHECK (factor > 0),
    CONSTRAINT rate_age_band_min_non_negative CHECK (min_age_years >= 0)
);

CREATE INDEX idx_rate_age_band_table ON rate_age_band (rate_table_id, min_age_years);

-- Claims-history surcharge, banded on the number of prior at-fault claims. Same half-open rule.
CREATE TABLE rate_claims_band (
    id             UUID          PRIMARY KEY,
    rate_table_id  UUID          NOT NULL REFERENCES rate_table (id) ON DELETE CASCADE,
    min_claims     INTEGER       NOT NULL,
    max_claims     INTEGER,
    factor         NUMERIC(9,6)  NOT NULL,

    CONSTRAINT rate_claims_band_range    CHECK (max_claims IS NULL OR max_claims > min_claims),
    CONSTRAINT rate_claims_band_positive CHECK (factor > 0),
    CONSTRAINT rate_claims_band_min_non_negative CHECK (min_claims >= 0)
);

CREATE INDEX idx_rate_claims_band_table ON rate_claims_band (rate_table_id, min_claims);

-- Human-readable quote references. A database sequence rather than a UUID because quote numbers
-- are read down the phone and typed into other systems, and rather than a count of rows because
-- a sequence keeps issuing distinct values under concurrency and after deletions.
CREATE SEQUENCE quote_number_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE quote (
    id                    UUID          PRIMARY KEY,
    quote_number          VARCHAR(32)   NOT NULL UNIQUE,
    product_code          VARCHAR(32)   NOT NULL,

    -- The date the risk was rated AS OF. Not the date the quote was created: re-rating the same
    -- risk for the same effective date must select the same rate table and produce the same
    -- premium, however long afterwards it is done.
    effective_date        DATE          NOT NULL,

    -- Which rate table produced this. Recorded explicitly so a premium can be traced to the
    -- exact published rates, even after newer versions exist.
    rate_table_id         UUID          NOT NULL REFERENCES rate_table (id),
    rate_table_version    INTEGER       NOT NULL,

    territory             VARCHAR(32)   NOT NULL,
    asset_age_years       INTEGER       NOT NULL,
    prior_claims_count    INTEGER       NOT NULL,
    policies_held         INTEGER       NOT NULL,

    total_premium_amount  NUMERIC(19,4) NOT NULL,
    total_premium_currency VARCHAR(3)   NOT NULL,

    created_at            TIMESTAMPTZ   NOT NULL,
    lock_version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT quote_premium_non_negative CHECK (total_premium_amount >= 0),
    CONSTRAINT quote_counts_non_negative
        CHECK (asset_age_years >= 0 AND prior_claims_count >= 0 AND policies_held >= 1)
);

CREATE INDEX idx_quote_rate_table ON quote (rate_table_id);

CREATE TABLE quote_coverage (
    id                  UUID          PRIMARY KEY,
    quote_id            UUID          NOT NULL REFERENCES quote (id) ON DELETE CASCADE,
    coverage_code       VARCHAR(32)   NOT NULL,
    limit_amount        NUMERIC(19,4) NOT NULL,
    limit_currency      VARCHAR(3)    NOT NULL,

    CONSTRAINT quote_coverage_unique UNIQUE (quote_id, coverage_code),
    CONSTRAINT quote_coverage_limit_non_negative CHECK (limit_amount >= 0)
);

-- The worksheet: the audit trail of the arithmetic.
CREATE TABLE quote_worksheet_line (
    id               UUID          PRIMARY KEY,
    quote_id         UUID          NOT NULL REFERENCES quote (id) ON DELETE CASCADE,

    -- Explicit ordering. The order factors are applied in is part of the answer, and relying on
    -- insertion order to reproduce it is relying on something the database never promised.
    step_number      INTEGER       NOT NULL,
    step_type        VARCHAR(32)   NOT NULL,
    description      VARCHAR(200)  NOT NULL,

    -- What went in, as displayed text: 'TX-DALLAS', '3 years', '50000.00 USD limit'.
    input_value      VARCHAR(100)  NOT NULL,
    -- The multiplier applied at this step. Null for additive base-rate lines.
    factor_value     NUMERIC(19,6),
    -- The running total after this step.
    subtotal_amount  NUMERIC(19,4) NOT NULL,
    subtotal_currency VARCHAR(3)   NOT NULL,

    CONSTRAINT quote_worksheet_line_unique_step UNIQUE (quote_id, step_number),
    CONSTRAINT quote_worksheet_step_type CHECK (
        step_type IN ('BASE_RATE', 'TERRITORY_FACTOR', 'ASSET_AGE_FACTOR',
                      'CLAIMS_HISTORY_SURCHARGE', 'MULTI_POLICY_DISCOUNT', 'MINIMUM_PREMIUM_FLOOR'))
);

CREATE INDEX idx_quote_worksheet_line_quote ON quote_worksheet_line (quote_id, step_number);
