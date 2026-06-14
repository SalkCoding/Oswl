-- Superseded by Flyway V9__import_webhook.sql. Kept for manual upgrades only.
-- Per-project VCS push webhook (auto re-scan on push).
-- Run manually on PostgreSQL when upgrading prod (ddl-auto=validate).

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS webhook_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS webhook_secret VARCHAR(64);
