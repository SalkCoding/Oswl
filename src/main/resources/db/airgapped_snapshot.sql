-- Air-gapped offline snapshot store (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies these automatically in local dev.
CREATE TABLE IF NOT EXISTS airgapped_snapshot_entries (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(20)  NOT NULL,
    entry_key   VARCHAR(600) NOT NULL,
    payload     TEXT         NOT NULL,
    CONSTRAINT uq_snapshot_entry_source_key UNIQUE (source, entry_key)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_entries_source_key ON airgapped_snapshot_entries (source, entry_key);

CREATE TABLE IF NOT EXISTS airgapped_snapshot_meta (
    source       VARCHAR(20) PRIMARY KEY,
    record_count BIGINT      NOT NULL DEFAULT 0,
    imported_at  TIMESTAMP   NOT NULL
);
