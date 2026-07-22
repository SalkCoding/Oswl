-- Embedded AI sidecar config overrides (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies this automatically in local dev.

ALTER TABLE ai_preferences
    ADD COLUMN IF NOT EXISTS embedded_dir VARCHAR(512);

ALTER TABLE ai_preferences
    ADD COLUMN IF NOT EXISTS embedded_model VARCHAR(255);
