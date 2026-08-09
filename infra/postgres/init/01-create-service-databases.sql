-- Creates one database per service, each owned by its own least-privilege role.
--
-- Service-per-database is enforced here rather than by convention: policy_svc simply has no
-- grant that would let it read aegis_claims. A shared database with three schemas would leave
-- a cross-service join one careless JOIN away, and that join is what turns three services back
-- into a distributed monolith that cannot be deployed independently.
--
-- Runs once, on the first startup of an empty postgres-data volume. `make reset` deletes the
-- volume to re-run it.

CREATE ROLE policy_svc WITH LOGIN PASSWORD 'policy_svc';
CREATE ROLE rating_svc WITH LOGIN PASSWORD 'rating_svc';
CREATE ROLE claims_svc WITH LOGIN PASSWORD 'claims_svc';

CREATE DATABASE aegis_policy OWNER policy_svc;
CREATE DATABASE aegis_rating OWNER rating_svc;
CREATE DATABASE aegis_claims OWNER claims_svc;

-- Revoke the implicit CONNECT that PUBLIC holds on every new database, so that "no
-- cross-service reads" is a property of the grants rather than a rule people remember.
REVOKE CONNECT ON DATABASE aegis_policy FROM PUBLIC;
REVOKE CONNECT ON DATABASE aegis_rating FROM PUBLIC;
REVOKE CONNECT ON DATABASE aegis_claims FROM PUBLIC;

GRANT CONNECT ON DATABASE aegis_policy TO policy_svc;
GRANT CONNECT ON DATABASE aegis_rating TO rating_svc;
GRANT CONNECT ON DATABASE aegis_claims TO claims_svc;
