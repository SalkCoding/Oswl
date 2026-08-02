-- Organization → Team → Project hierarchy. Run once on PostgreSQL before deploy with ddl-auto=validate.
-- Mirrors db/migration/V11__org_team_hierarchy.sql for deployments that manage the schema by hand
-- instead of enabling Flyway.

CREATE TABLE IF NOT EXISTS organizations (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- Single-organization deployment: exactly one row.
INSERT INTO organizations (name, created_at)
SELECT 'Default Organization', now()
WHERE NOT EXISTS (SELECT 1 FROM organizations);

CREATE TABLE IF NOT EXISTS teams (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000),
    parent_team_id  BIGINT       REFERENCES teams(id) ON DELETE SET NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_teams_org_name UNIQUE (organization_id, name)
);
CREATE INDEX IF NOT EXISTS idx_teams_parent_team_id ON teams (parent_team_id);

-- Every org gets a "Default" team so pre-hierarchy projects have somewhere to land.
INSERT INTO teams (organization_id, name, description, created_at)
SELECT o.id, 'Default', 'Projects created before teams were introduced.', now()
FROM organizations o
WHERE NOT EXISTS (SELECT 1 FROM teams t WHERE t.organization_id = o.id AND t.name = 'Default');

CREATE TABLE IF NOT EXISTS team_members (
    id          BIGSERIAL PRIMARY KEY,
    team_id     BIGINT      NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_members_team_user UNIQUE (team_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_team_members_user_id ON team_members (user_id);

ALTER TABLE projects ADD COLUMN IF NOT EXISTS team_id BIGINT REFERENCES teams(id) ON DELETE SET NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS tags VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_projects_team_id ON projects (team_id);

-- Fold all existing projects into the Default team. Direct project_members grants are
-- untouched, so access after migration is identical to access before it.
UPDATE projects
SET team_id = (SELECT t.id FROM teams t WHERE t.name = 'Default' ORDER BY t.id LIMIT 1)
WHERE team_id IS NULL
  AND EXISTS (SELECT 1 FROM teams t WHERE t.name = 'Default');
