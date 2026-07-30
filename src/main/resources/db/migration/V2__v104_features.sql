-- OsWL v1.0.4 additive schema (roadmap #13 Flyway introduction).
--
-- Adoption path: Flyway is opt-in (OSWL_FLYWAY_ENABLED, default false). When first enabled on an
-- existing database, baseline-on-migrate marks the current schema at V1 and applies V2+ forward.
-- ddl-auto=validate (prod) then confirms the entities match. For a brand-new database, generate a
-- full V1 baseline from the JPA schema before disabling ddl-auto.
--
-- All statements are idempotent (IF NOT EXISTS) so this migration is safe to (re)apply.

-- ── Continuous monitoring alerts (#4) ──
CREATE TABLE IF NOT EXISTS cve_alerts (
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT      NOT NULL REFERENCES projects(id),
    library_id       BIGINT      NOT NULL REFERENCES libraries(id),
    library_name     VARCHAR(300) NOT NULL,
    library_version  VARCHAR(100),
    ecosystem        VARCHAR(20),
    vuln_id          VARCHAR(40) NOT NULL,
    cve_id           VARCHAR(30),
    severity         VARCHAR(10),
    summary          TEXT,
    fix_version      VARCHAR(100),
    detected_at      TIMESTAMP   NOT NULL DEFAULT now(),
    notified         BOOLEAN     NOT NULL DEFAULT FALSE,
    notified_at      TIMESTAMP,
    acknowledged     BOOLEAN     NOT NULL DEFAULT FALSE,
    acknowledged_at  TIMESTAMP,
    CONSTRAINT uq_cve_alerts_project_library_vuln UNIQUE (project_id, library_id, vuln_id)
);
CREATE INDEX IF NOT EXISTS idx_cve_alerts_project_ack ON cve_alerts (project_id, acknowledged);

-- ── Package health / supply-chain (#5, #6, #16) ──
ALTER TABLE libraries      ADD COLUMN IF NOT EXISTS scorecard_score   DOUBLE PRECISION;
ALTER TABLE libraries      ADD COLUMN IF NOT EXISTS malicious         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE libraries      ADD COLUMN IF NOT EXISTS typosquat_risk    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE libraries      ADD COLUMN IF NOT EXISTS typosquat_reason  VARCHAR(300);
ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS scope            VARCHAR(20);
ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS jira_issue_key   VARCHAR(50);
ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS jira_issue_url   VARCHAR(500);

-- ── Jira integration (#10) ──
CREATE TABLE IF NOT EXISTS jira_settings (
    id           BIGSERIAL PRIMARY KEY,
    base_url     VARCHAR(300),
    email        VARCHAR(255),
    api_token    VARCHAR(1000),
    project_key  VARCHAR(50),
    issue_type   VARCHAR(50),
    enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMP
);

-- ── Air-gapped offline snapshot (#11) ──
-- Table names must be airgapped_snapshot_* to match SnapshotMeta/SnapshotEntry. An earlier
-- revision of this migration created them as snapshot_meta/snapshot_entries, which no entity
-- maps to — that left the v2-format columns V7 adds (`ALTER TABLE airgapped_snapshot_meta ...`)
-- pointing at a table no migration had created, so any deployment without a pre-existing
-- ddl-auto schema failed at V7.
-- The provenance columns (bundle_id/built_at/source_as_of/origin/format_version) are
-- deliberately NOT here: V7 adds them, and this file must keep representing the pre-V7 shape.
CREATE TABLE IF NOT EXISTS airgapped_snapshot_meta (
    source        VARCHAR(20) NOT NULL,
    record_count  BIGINT      NOT NULL DEFAULT 0,
    imported_at   TIMESTAMP   NOT NULL,
    CONSTRAINT pk_airgapped_snapshot_meta PRIMARY KEY (source)
);
CREATE TABLE IF NOT EXISTS airgapped_snapshot_entries (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(20)  NOT NULL,
    entry_key   VARCHAR(600) NOT NULL,
    payload     TEXT         NOT NULL,
    CONSTRAINT uq_snapshot_entry_source_key UNIQUE (source, entry_key)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_entries_source_key ON airgapped_snapshot_entries (source, entry_key);
