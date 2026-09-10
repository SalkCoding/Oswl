-- Audit log integrity hash chain.
-- Adds previous-hash and current-hash columns so every audit log entry can be
-- cryptographically linked to the entry that preceded it.
--
-- All statements are idempotent so this migration is safe to reapply.

ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_audit_logs_hash ON audit_logs (hash);
