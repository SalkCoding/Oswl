-- Expose the evidence backing a REACHABLE verdict — up to a handful of
-- "your class X references library class Y" lines from the bytecode call-graph analysis,
-- rather than storing only the REACHABLE/NOT_REACHABLE/UNKNOWN enum with no way to verify it.
-- Null for NOT_REACHABLE/UNKNOWN, where there's nothing to point to.

ALTER TABLE scan_components ADD COLUMN IF NOT EXISTS reachability_evidence TEXT;
