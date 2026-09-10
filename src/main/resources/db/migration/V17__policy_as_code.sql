-- Policy as Code + waiver/exception approval workflow.
--
-- Policies compose hierarchically: organization → team → project.
-- A locked policy prevents lower levels from overriding the fields it defines.
-- Policy exceptions are approval-driven waivers that suppress matching gate findings
-- until they expire.
--
-- All statements are idempotent so this migration is safe to reapply.

CREATE TABLE IF NOT EXISTS policies (
    id                           BIGSERIAL PRIMARY KEY,
    scope                        VARCHAR(20)  NOT NULL,
    organization_id              BIGINT       REFERENCES organizations(id) ON DELETE CASCADE,
    team_id                      BIGINT       REFERENCES teams(id) ON DELETE CASCADE,
    project_id                   BIGINT       REFERENCES projects(id) ON DELETE CASCADE,
    name                         VARCHAR(200) NOT NULL,
    description                  VARCHAR(1000),
    locked                       BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled                      BOOLEAN      NOT NULL DEFAULT TRUE,
    fail_on_severity             VARCHAR(20),
    fail_on_kev                  BOOLEAN,
    fail_on_epss                DOUBLE PRECISION,
    fail_on_license_violation    BOOLEAN,
    only_new                     BOOLEAN,
    created_at                   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_policies_organization UNIQUE (organization_id),
    CONSTRAINT uq_policies_team UNIQUE (team_id),
    CONSTRAINT uq_policies_project UNIQUE (project_id),
    CONSTRAINT chk_policies_single_scope CHECK (
        (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND team_id IS NULL AND project_id IS NULL) OR
        (scope = 'TEAM'         AND team_id IS NOT NULL         AND organization_id IS NULL AND project_id IS NULL) OR
        (scope = 'PROJECT'      AND project_id IS NOT NULL      AND organization_id IS NULL AND team_id IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_policies_scope ON policies (scope);
CREATE INDEX IF NOT EXISTS idx_policies_team_id ON policies (team_id);
CREATE INDEX IF NOT EXISTS idx_policies_project_id ON policies (project_id);

CREATE TABLE IF NOT EXISTS policy_exceptions (
    id                    BIGSERIAL PRIMARY KEY,
    project_id            BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    requester_user_id     BIGINT       NOT NULL,
    requester_name        VARCHAR(100),
    approver_user_id      BIGINT,
    approver_name         VARCHAR(100),
    reason                VARCHAR(1000) NOT NULL,
    expiry                TIMESTAMP    NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    target_type           VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    target_id             VARCHAR(200),
    component_coordinate  VARCHAR(300),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    approved_at           TIMESTAMP,
    revoked_at            TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_policy_exceptions_project_status ON policy_exceptions (project_id, status);
CREATE INDEX IF NOT EXISTS idx_policy_exceptions_status_expiry ON policy_exceptions (status, expiry);
CREATE INDEX IF NOT EXISTS idx_policy_exceptions_target ON policy_exceptions (component_coordinate, target_type, target_id);
