-- Project-scoped ACL (Sprint 3). Run once on PostgreSQL before deploy with ddl-auto=validate.

CREATE TABLE IF NOT EXISTS project_members (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_members_user_id ON project_members (user_id);
