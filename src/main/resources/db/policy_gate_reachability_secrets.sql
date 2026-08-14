-- DDL reference for ROADMAP A7's onlyReachable/failOnSecrets policy columns.
-- Applied via Flyway in V28__policy_gate_reachability_secrets.sql; kept here as a ddl-auto reference.

ALTER TABLE policies ADD COLUMN IF NOT EXISTS only_reachable BOOLEAN;
ALTER TABLE policies ADD COLUMN IF NOT EXISTS fail_on_secrets BOOLEAN;
