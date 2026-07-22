-- AI per-call usage events (run once on PostgreSQL when ddl-auto=validate).
CREATE TABLE IF NOT EXISTS ai_usage_events (
    id                  BIGSERIAL PRIMARY KEY,
    usage_date          DATE         NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provider            VARCHAR(32)  NOT NULL,
    operation           VARCHAR(64)  NOT NULL,
    prompt_tokens       INT          NOT NULL DEFAULT 0,
    completion_tokens   INT          NOT NULL DEFAULT 0,
    total_tokens        INT          NOT NULL DEFAULT 0,
    estimated_cost_usd  NUMERIC(12,6) NOT NULL DEFAULT 0,
    model_name          VARCHAR(128),
    project_name        VARCHAR(160)
);
CREATE INDEX IF NOT EXISTS ix_ai_usage_events_date_provider ON ai_usage_events (usage_date, provider);
CREATE INDEX IF NOT EXISTS ix_ai_usage_events_date_created ON ai_usage_events (usage_date, created_at);

-- Upgrade note: existing installs created before the project_name column was added
-- (2026-07-22) need it added explicitly since ddl-auto=validate never alters tables.
ALTER TABLE ai_usage_events ADD COLUMN IF NOT EXISTS project_name VARCHAR(160);
