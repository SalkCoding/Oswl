-- AI enhancement schema (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies these automatically in local dev.

ALTER TABLE ai_preferences
    ADD COLUMN IF NOT EXISTS temperature DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS max_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS daily_call_cap INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prompt_overrides TEXT,
    ADD COLUMN IF NOT EXISTS default_deployment_profile VARCHAR(40) NOT NULL DEFAULT 'COMMERCIAL_PRODUCT';

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS deployment_profile VARCHAR(40);

ALTER TABLE library_cves
    ADD COLUMN IF NOT EXISTS ai_priority VARCHAR(10),
    ADD COLUMN IF NOT EXISTS ai_recommended_action TEXT,
    ADD COLUMN IF NOT EXISTS epss_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS kev_listed BOOLEAN;

CREATE TABLE IF NOT EXISTS ai_daily_usage (
    id BIGSERIAL PRIMARY KEY,
    usage_date DATE NOT NULL,
    provider VARCHAR(32) NOT NULL,
    call_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_ai_daily_usage UNIQUE (usage_date, provider)
);

CREATE TABLE IF NOT EXISTS ai_feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    target_type VARCHAR(32) NOT NULL,
    target_key VARCHAR(120) NOT NULL,
    helpful BOOLEAN NOT NULL,
    comment VARCHAR(500),
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_feedback_target ON ai_feedback (target_type, target_key);
