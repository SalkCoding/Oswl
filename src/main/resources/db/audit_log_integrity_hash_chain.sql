-- Audit log integrity hash chain (ddl-auto=validate reference).
-- Run on PostgreSQL when Flyway is disabled.
-- Statements are idempotent so they are safe to reapply.

ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_audit_logs_hash ON audit_logs (hash);
