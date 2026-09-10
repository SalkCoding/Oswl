-- Named Security Center filter/sort combinations, scoped per project.
-- Run once on PostgreSQL before deploy with ddl-auto=validate.

CREATE TABLE IF NOT EXISTS saved_views (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_by_user_id  BIGINT NOT NULL,
    created_by_name     VARCHAR(150) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    filters_json        TEXT NOT NULL,
    shared              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_saved_views_project_id ON saved_views (project_id);
