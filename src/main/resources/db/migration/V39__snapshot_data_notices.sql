-- Preserve supplied notices across offline imports and subsequent exports.
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS data_notices TEXT;
