-- OsWL v1.0.5 — Slack/Teams incoming webhook notification settings and delivery history.
--
-- Flyway V14 (reserved). The statements are idempotent so they are safe to reapply.

-- ── Webhook configuration (single-row settings) ──
CREATE TABLE IF NOT EXISTS webhook_settings (
    id                       BIGSERIAL PRIMARY KEY,
    provider                 VARCHAR(20)  NOT NULL,
    webhook_url              VARCHAR(1000),
    enabled                  BOOLEAN      NOT NULL DEFAULT FALSE,
    notify_new_cve           BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_gate_failure      BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_scan_failure      BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_waiver_expiry     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP,
    updated_at               TIMESTAMP
);

-- ── Webhook delivery history (retry / failure exposure) ──
CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id               BIGSERIAL PRIMARY KEY,
    event_type       VARCHAR(30)  NOT NULL,
    project_id       BIGINT,
    project_name     VARCHAR(160),
    reference_id     VARCHAR(40),
    status           VARCHAR(20)  NOT NULL,
    http_status      INTEGER,
    error_message    VARCHAR(500),
    retry_count      INTEGER      NOT NULL DEFAULT 0,
    payload_summary  VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_created_at ON webhook_deliveries (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_status ON webhook_deliveries (status);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_event_type ON webhook_deliveries (event_type);
