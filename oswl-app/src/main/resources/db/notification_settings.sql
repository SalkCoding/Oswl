-- Superseded by Flyway V10__notification_settings.sql (kept for reference on legacy manual upgrades).

CREATE TABLE IF NOT EXISTS notification_settings (
    id                      BIGINT PRIMARY KEY,
    slack_webhook_url       VARCHAR(1000),
    teams_webhook_url       VARCHAR(1000),
    email_digest_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    notify_critical_cve     BOOLEAN NOT NULL DEFAULT TRUE,
    notify_license_violation BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at              TIMESTAMP
);

INSERT INTO notification_settings (id, email_digest_enabled, notify_critical_cve, notify_license_violation)
VALUES (1, FALSE, TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;
