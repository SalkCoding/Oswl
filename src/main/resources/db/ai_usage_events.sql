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
    model_name          VARCHAR(128)
);
CREATE INDEX IF NOT EXISTS ix_ai_usage_events_date_provider ON ai_usage_events (usage_date, provider);
