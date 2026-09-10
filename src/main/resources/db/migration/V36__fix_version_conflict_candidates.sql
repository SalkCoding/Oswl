-- Keep conflicting remediation candidates across single-source refreshes.
CREATE TABLE IF NOT EXISTS library_cve_fix_conflicts (
    cve_id BIGINT NOT NULL REFERENCES library_cves(id) ON DELETE CASCADE,
    candidate VARCHAR(100) NOT NULL,
    PRIMARY KEY (cve_id, candidate)
);
