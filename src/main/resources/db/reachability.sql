-- DDL reference for the reachability column.
-- Applied via Flyway in V18__reachability.sql; kept here as a ddl-auto reference.

ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS reachability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

-- Evidence backing a REACHABLE verdict. Applied via Flyway in V29__reachability_evidence.sql.
ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS reachability_evidence TEXT;
