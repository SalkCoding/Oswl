-- DDL reference for ROADMAP A2 reachability column.
-- Applied via Flyway in V18__reachability.sql; kept here as a ddl-auto reference.

ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS reachability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
