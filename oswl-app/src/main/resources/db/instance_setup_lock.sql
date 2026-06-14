-- Superseded by Flyway V11__instance_setup_lock.sql. Kept for manual upgrades only.
-- Run once when upgrading production (ddl-auto=validate).
-- Reserves initial /setup to a single winner under concurrent requests.

CREATE TABLE IF NOT EXISTS instance_setup_lock (
    id           BIGINT       NOT NULL PRIMARY KEY,
    completed_at TIMESTAMPTZ  NOT NULL
);
