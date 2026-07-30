-- Embedded-vs-external marker on the LOCAL AI provider row
-- (run once on PostgreSQL when ddl-auto=validate; ddl-auto=update applies it automatically).
-- Null on existing rows means "user-configured endpoint".

ALTER TABLE ai_settings
    ADD COLUMN IF NOT EXISTS embedded_managed BOOLEAN;
