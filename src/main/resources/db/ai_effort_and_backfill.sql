-- AI reasoning effort + insight backfill opt-in (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies this automatically in local dev.
-- Null on existing rows means "no effort parameter" and "do not auto-regenerate" respectively.

ALTER TABLE ai_preferences
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(20);

ALTER TABLE ai_preferences
    ADD COLUMN IF NOT EXISTS auto_backfill_insights BOOLEAN;
