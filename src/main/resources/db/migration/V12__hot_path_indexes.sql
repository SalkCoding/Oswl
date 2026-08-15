-- Hot-path index backfill.
--
-- All statements are idempotent (IF NOT EXISTS), matching the V2+ convention, so this is a
-- no-op wherever an index already exists (e.g. idx_project_members_user_id on databases built
-- from the V1 baseline or by ddl-auto=update, which picked it up from the entity annotation).

-- scan_results had no index declaration at all. This composite serves the version-history and
-- risk-trend queries: ScanResultRepository.findCompletedByProjectId and findRecentCompleted
-- (WHERE project_id = ? AND status = 'COMPLETED' ORDER BY scanned_at DESC).
CREATE INDEX IF NOT EXISTS idx_scan_results_project_status_scanned
    ON scan_results (project_id, status, scanned_at DESC);

-- Serves project_id-only scans ordered by scanned_at across mixed statuses, which the composite
-- above cannot order: ScanResultRepository.findLatestByProjectId (status polling banner),
-- findAllByProjectIdOrderByScannedAtDesc (scan history page), findByProjectIdAndVersion (upsert).
CREATE INDEX IF NOT EXISTS idx_scan_results_project_scanned
    ON scan_results (project_id, scanned_at DESC);

-- Serves the AI insight backfill: ScanResultRepository.findTop15ByStatusOrderByScannedAtDesc
-- (WHERE status = ? ORDER BY scanned_at DESC across all projects).
CREATE INDEX IF NOT EXISTS idx_scan_results_status_scanned
    ON scan_results (status, scanned_at DESC);

-- project_members is hit on every permission check. Normally already present (declared on the
-- entity and in the V1 baseline); included defensively for pre-baseline deployments.
-- Serves ProjectMemberRepository.findProjectIdsByUserId, findAccessibleProjectIds, deleteByUserId.
CREATE INDEX IF NOT EXISTS idx_project_members_user_id
    ON project_members (user_id);

-- All project list/search/trash/auto-cleanup queries filter on deleted_at:
-- ProjectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc (list),
-- findAllByDeletedAtIsNotNullOrderByDeletedAtAsc (trash), findAllByDeletedAtBefore (cleanup).
CREATE INDEX IF NOT EXISTS idx_projects_deleted_at
    ON projects (deleted_at);

-- API keys are listed/counted per project on the settings page:
-- ApiKeyRepository.findByProjectIdOrderByCreatedAtDesc, countByProjectId.
CREATE INDEX IF NOT EXISTS idx_api_keys_project_id
    ON api_keys (project_id);

-- VCS connections are loaded per user on the VCS settings page and on user deletion:
-- UserVcsConnectionRepository.findByUserIdAndActiveTrue, deleteByUser_Id.
CREATE INDEX IF NOT EXISTS idx_user_vcs_connections_user_id
    ON user_vcs_connections (user_id);
