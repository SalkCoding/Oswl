ALTER TABLE scan_results ADD COLUMN snapshot_generation_id BIGINT REFERENCES snapshot_generations(id);
CREATE INDEX idx_scan_snapshot_generation ON scan_results(snapshot_generation_id);
