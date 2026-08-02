-- ROADMAP A8: C/C++ real coverage reference script.
-- Run once on PostgreSQL when ddl-auto=validate. Idempotent.

ALTER TABLE libraries ADD COLUMN IF NOT EXISTS cpe TEXT;
