-- SAML 2.0 and SCIM 2.0 support. Run once on PostgreSQL before deploy with ddl-auto=validate.
-- Mirrors db/migration/V13__saml_scim.sql for deployments that manage the schema by hand
-- instead of enabling Flyway.

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'PROJECT';
ALTER TABLE api_keys ALTER COLUMN project_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_api_keys_scope ON api_keys (scope);
