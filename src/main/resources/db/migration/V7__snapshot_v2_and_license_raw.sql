-- OsWL performance/offline plan E1/E4 — snapshot bundle v2 provenance/integrity, and the
-- pre-join per-license list so a multi-license package round-trips exactly through an
-- offline snapshot export/import instead of being re-split from "MIT AND Apache-2.0" text.
--
-- Null on every pre-existing row (idempotent, matching V2-V6):
-- - libraries.license_expression_raw: legacy rows fall back to splitting license_name on
--   " AND " (see AirgappedSnapshotService) until the next enrichment repopulates this column.
-- - airgapped_snapshot_meta's new columns: legacy (v1 format) snapshot rows simply have no
--   provenance to show — AirgappedSnapshotService treats formatVersion IS NULL as v1.

ALTER TABLE libraries ADD COLUMN IF NOT EXISTS license_expression_raw TEXT;

ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS bundle_id VARCHAR(64);
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS built_at TIMESTAMP;
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS source_as_of DATE;
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS origin VARCHAR(100);
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS format_version INT;
