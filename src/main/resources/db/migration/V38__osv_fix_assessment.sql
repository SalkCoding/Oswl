-- Retain the OSV-only common fix decision separately from individual CVE fixes.
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS osv_fix_assessment TEXT;
