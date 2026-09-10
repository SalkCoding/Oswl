-- Hot-path index backfill (run once on PostgreSQL when ddl-auto=validate).
-- Hibernate ddl-auto=update applies the entity-annotated indexes automatically in local dev;
-- this script mirrors Flyway migration V12 for production deployments.
-- All statements are idempotent (IF NOT EXISTS).

CREATE INDEX IF NOT EXISTS idx_scan_results_project_status_scanned
    ON scan_results (project_id, status, scanned_at DESC);

CREATE INDEX IF NOT EXISTS idx_scan_results_project_scanned
    ON scan_results (project_id, scanned_at DESC);

CREATE INDEX IF NOT EXISTS idx_scan_results_status_scanned
    ON scan_results (status, scanned_at DESC);

CREATE INDEX IF NOT EXISTS idx_project_members_user_id
    ON project_members (user_id);

CREATE INDEX IF NOT EXISTS idx_projects_deleted_at
    ON projects (deleted_at);

CREATE INDEX IF NOT EXISTS idx_api_keys_project_id
    ON api_keys (project_id);

CREATE INDEX IF NOT EXISTS idx_user_vcs_connections_user_id
    ON user_vcs_connections (user_id);
