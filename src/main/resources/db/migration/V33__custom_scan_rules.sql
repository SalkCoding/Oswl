CREATE TABLE IF NOT EXISTS custom_scan_rules (
    id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL,
    rules_json TEXT NOT NULL
);
