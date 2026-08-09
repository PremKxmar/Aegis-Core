-- Published rate tables.
--
-- Seeded as a migration rather than by an admin API because published rates are reference data
-- with a release cycle: they are reviewed, filed with a regulator, and then frozen. Putting them
-- in version control means a premium can be traced to a commit.
--
-- Two versions of PERSONAL_AUTO are seeded on purpose, with adjacent effective periods, so that
-- rating the same risk for 2025-06-01 and 2026-06-01 legitimately produces different premiums —
-- and so the effective-dated lookup is exercised by real data rather than only by tests.
--
-- Fixed UUIDs, not gen_random_uuid(): a quote records which rate table produced it, and stable
-- identifiers keep that reference meaningful across environments.

-- ---------------------------------------------------------------------------------------------
-- PERSONAL_AUTO version 1 — effective 2025-01-01 until 2026-01-01
-- ---------------------------------------------------------------------------------------------
INSERT INTO rate_table (id, product_code, table_version, effective_from, effective_to, published_at,
                        multi_policy_discount_factor, minimum_premium_amount, minimum_premium_currency)
VALUES ('a0000000-0000-4000-8000-000000000001', 'PERSONAL_AUTO', 1, '2025-01-01', '2026-01-01',
        '2024-11-15T00:00:00Z', 0.900000, 250.0000, 'USD');

INSERT INTO rate_base (id, rate_table_id, coverage_code, rate_per_thousand) VALUES
    ('b0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'COLLISION',     4.500000),
    ('b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001', 'COMPREHENSIVE', 2.750000),
    ('b0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000001', 'LIABILITY',     3.200000);

INSERT INTO rate_territory (id, rate_table_id, territory, factor) VALUES
    ('c0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'TX-DALLAS',   1.150000),
    ('c0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001', 'TX-AUSTIN',   1.050000),
    ('c0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000001', 'CA-LA',       1.400000),
    ('c0000000-0000-4000-8000-000000000004', 'a0000000-0000-4000-8000-000000000001', 'IA-RURAL',    0.850000);

-- Bands are half-open: min <= age < max. NULL max means open-ended.
INSERT INTO rate_age_band (id, rate_table_id, min_age_years, max_age_years, factor) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001',  0,    3, 1.000000),
    ('d0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001',  3,    8, 1.100000),
    ('d0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000001',  8,   15, 1.250000),
    ('d0000000-0000-4000-8000-000000000004', 'a0000000-0000-4000-8000-000000000001', 15, NULL, 1.450000);

INSERT INTO rate_claims_band (id, rate_table_id, min_claims, max_claims, factor) VALUES
    ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 0,    1, 1.000000),
    ('e0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001', 1,    2, 1.250000),
    ('e0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000001', 2,    4, 1.600000),
    ('e0000000-0000-4000-8000-000000000004', 'a0000000-0000-4000-8000-000000000001', 4, NULL, 2.100000);

-- ---------------------------------------------------------------------------------------------
-- PERSONAL_AUTO version 2 — effective 2026-01-01 onward. A rate rise, as filed.
-- ---------------------------------------------------------------------------------------------
INSERT INTO rate_table (id, product_code, table_version, effective_from, effective_to, published_at,
                        multi_policy_discount_factor, minimum_premium_amount, minimum_premium_currency)
VALUES ('a0000000-0000-4000-8000-000000000002', 'PERSONAL_AUTO', 2, '2026-01-01', NULL,
        '2025-11-20T00:00:00Z', 0.880000, 300.0000, 'USD');

INSERT INTO rate_base (id, rate_table_id, coverage_code, rate_per_thousand) VALUES
    ('b0000000-0000-4000-8000-000000000011', 'a0000000-0000-4000-8000-000000000002', 'COLLISION',     4.950000),
    ('b0000000-0000-4000-8000-000000000012', 'a0000000-0000-4000-8000-000000000002', 'COMPREHENSIVE', 2.900000),
    ('b0000000-0000-4000-8000-000000000013', 'a0000000-0000-4000-8000-000000000002', 'LIABILITY',     3.450000);

INSERT INTO rate_territory (id, rate_table_id, territory, factor) VALUES
    ('c0000000-0000-4000-8000-000000000011', 'a0000000-0000-4000-8000-000000000002', 'TX-DALLAS',   1.180000),
    ('c0000000-0000-4000-8000-000000000012', 'a0000000-0000-4000-8000-000000000002', 'TX-AUSTIN',   1.070000),
    ('c0000000-0000-4000-8000-000000000013', 'a0000000-0000-4000-8000-000000000002', 'CA-LA',       1.450000),
    ('c0000000-0000-4000-8000-000000000014', 'a0000000-0000-4000-8000-000000000002', 'IA-RURAL',    0.870000);

INSERT INTO rate_age_band (id, rate_table_id, min_age_years, max_age_years, factor) VALUES
    ('d0000000-0000-4000-8000-000000000011', 'a0000000-0000-4000-8000-000000000002',  0,    3, 1.000000),
    ('d0000000-0000-4000-8000-000000000012', 'a0000000-0000-4000-8000-000000000002',  3,    8, 1.120000),
    ('d0000000-0000-4000-8000-000000000013', 'a0000000-0000-4000-8000-000000000002',  8,   15, 1.300000),
    ('d0000000-0000-4000-8000-000000000014', 'a0000000-0000-4000-8000-000000000002', 15, NULL, 1.500000);

INSERT INTO rate_claims_band (id, rate_table_id, min_claims, max_claims, factor) VALUES
    ('e0000000-0000-4000-8000-000000000011', 'a0000000-0000-4000-8000-000000000002', 0,    1, 1.000000),
    ('e0000000-0000-4000-8000-000000000012', 'a0000000-0000-4000-8000-000000000002', 1,    2, 1.300000),
    ('e0000000-0000-4000-8000-000000000013', 'a0000000-0000-4000-8000-000000000002', 2,    4, 1.700000),
    ('e0000000-0000-4000-8000-000000000014', 'a0000000-0000-4000-8000-000000000002', 4, NULL, 2.200000);

-- ---------------------------------------------------------------------------------------------
-- HOMEOWNERS version 1 — the same engine rating a property rather than a vehicle. "Asset age"
-- is the age of the roof or the building; nothing in the rules changes.
-- ---------------------------------------------------------------------------------------------
INSERT INTO rate_table (id, product_code, table_version, effective_from, effective_to, published_at,
                        multi_policy_discount_factor, minimum_premium_amount, minimum_premium_currency)
VALUES ('a0000000-0000-4000-8000-000000000003', 'HOMEOWNERS', 1, '2025-01-01', NULL,
        '2024-12-01T00:00:00Z', 0.850000, 400.0000, 'USD');

INSERT INTO rate_base (id, rate_table_id, coverage_code, rate_per_thousand) VALUES
    ('b0000000-0000-4000-8000-000000000021', 'a0000000-0000-4000-8000-000000000003', 'DWELLING',        1.850000),
    ('b0000000-0000-4000-8000-000000000022', 'a0000000-0000-4000-8000-000000000003', 'PERSONAL_PROPERTY', 2.400000),
    ('b0000000-0000-4000-8000-000000000023', 'a0000000-0000-4000-8000-000000000003', 'LIABILITY',       0.950000);

INSERT INTO rate_territory (id, rate_table_id, territory, factor) VALUES
    ('c0000000-0000-4000-8000-000000000021', 'a0000000-0000-4000-8000-000000000003', 'TX-DALLAS',   1.250000),
    ('c0000000-0000-4000-8000-000000000022', 'a0000000-0000-4000-8000-000000000003', 'TX-COASTAL',  1.900000),
    ('c0000000-0000-4000-8000-000000000023', 'a0000000-0000-4000-8000-000000000003', 'IA-RURAL',    0.800000);

INSERT INTO rate_age_band (id, rate_table_id, min_age_years, max_age_years, factor) VALUES
    ('d0000000-0000-4000-8000-000000000021', 'a0000000-0000-4000-8000-000000000003',  0,   10, 1.000000),
    ('d0000000-0000-4000-8000-000000000022', 'a0000000-0000-4000-8000-000000000003', 10,   30, 1.150000),
    ('d0000000-0000-4000-8000-000000000023', 'a0000000-0000-4000-8000-000000000003', 30, NULL, 1.400000);

INSERT INTO rate_claims_band (id, rate_table_id, min_claims, max_claims, factor) VALUES
    ('e0000000-0000-4000-8000-000000000021', 'a0000000-0000-4000-8000-000000000003', 0,    1, 1.000000),
    ('e0000000-0000-4000-8000-000000000022', 'a0000000-0000-4000-8000-000000000003', 1,    3, 1.350000),
    ('e0000000-0000-4000-8000-000000000023', 'a0000000-0000-4000-8000-000000000003', 3, NULL, 1.950000);
