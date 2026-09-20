-- Preserve declared distribution restrictions without approving legacy data.
ALTER TABLE airgapped_snapshot_meta ADD COLUMN IF NOT EXISTS distribution_profile VARCHAR(32);
