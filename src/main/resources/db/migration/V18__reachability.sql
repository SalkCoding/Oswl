-- Java call-graph reachability analysis.
--
-- Stores the result of bytecode reachability analysis for each scan component.
-- UNKNOWN is the default for scans that pre-date this feature or for which no
-- project bytecode was supplied.

ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS reachability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
