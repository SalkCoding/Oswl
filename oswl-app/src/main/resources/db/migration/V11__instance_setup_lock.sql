CREATE TABLE IF NOT EXISTS instance_setup_lock (
    id           BIGINT       NOT NULL PRIMARY KEY,
    completed_at TIMESTAMPTZ  NOT NULL
);
