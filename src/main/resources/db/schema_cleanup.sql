-- Schema cleanup (run once on PostgreSQL when ddl-auto=validate).
-- Drops unused tables/columns removed from JPA entities.

DROP TABLE IF EXISTS ai_feedback;

ALTER TABLE api_keys DROP COLUMN IF EXISTS created_by_user_id;

ALTER TABLE scan_results DROP COLUMN IF EXISTS raw_payload;
ALTER TABLE scan_results DROP COLUMN IF EXISTS submitted_by_user_id;

ALTER TABLE project_versions DROP COLUMN IF EXISTS imported_at;
ALTER TABLE project_versions DROP COLUMN IF EXISTS last_updated_at;

ALTER TABLE projects DROP COLUMN IF EXISTS updated_at;
ALTER TABLE projects DROP COLUMN IF EXISTS version;
ALTER TABLE projects DROP COLUMN IF EXISTS last_scanned_at;

DROP TABLE IF EXISTS external_api_settings;
