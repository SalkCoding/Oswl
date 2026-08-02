-- CVE multi-source attribution + CPE match confidence (ROADMAP A1).
--
-- CVEs can now be contributed by OSV, GitHub Advisory, and NVD. Each contributing source
-- is recorded in library_cve_sources so the UI can show "where this finding came from".
-- severity_conflict flags cases where sources disagree on severity.
-- match_confidence is used for CPE-based NVD lookups on vendored/system libraries.

ALTER TABLE library_cves ADD COLUMN IF NOT EXISTS severity_conflict BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE library_cves ADD COLUMN IF NOT EXISTS match_confidence VARCHAR(10);

CREATE TABLE IF NOT EXISTS library_cve_sources (
    cve_id BIGINT       NOT NULL REFERENCES library_cves(id) ON DELETE CASCADE,
    source VARCHAR(20)  NOT NULL,
    PRIMARY KEY (cve_id, source)
);

CREATE INDEX IF NOT EXISTS idx_library_cve_sources_cve_id ON library_cve_sources (cve_id);
