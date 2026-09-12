ALTER TABLE scan_results ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE scan_results ADD COLUMN input_digest VARCHAR(64);
ALTER TABLE scan_results ADD CONSTRAINT uq_scan_project_retry_key UNIQUE (project_id, idempotency_key);
