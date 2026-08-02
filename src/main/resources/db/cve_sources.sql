-- CVE multi-source attribution + CPE match confidence reference script (ROADMAP A1).
-- Run once on PostgreSQL when ddl-auto=validate. All statements are idempotent.

ALTER TABLE library_cves ADD COLUMN IF NOT EXISTS severity_conflict BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE library_cves ADD COLUMN IF NOT EXISTS match_confidence VARCHAR(10);

CREATE TABLE IF NOT EXISTS library_cve_sources (
    cve_id BIGINT       NOT NULL REFERENCES library_cves(id) ON DELETE CASCADE,
    source VARCHAR(20)  NOT NULL,
    PRIMARY KEY (cve_id, source)
);

CREATE INDEX IF NOT EXISTS idx_library_cve_sources_cve_id ON library_cve_sources (cve_id);
