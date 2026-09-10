-- Retain multiple conditional dependency declarations without truncating their evidence.
ALTER TABLE scan_components ALTER COLUMN dependency_info TYPE TEXT;
