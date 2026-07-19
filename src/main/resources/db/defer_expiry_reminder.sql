-- Deferral expiry reminder (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies this automatically in local dev.

ALTER TABLE scan_components
    ADD COLUMN IF NOT EXISTS deferral_expired_at TIMESTAMP;
