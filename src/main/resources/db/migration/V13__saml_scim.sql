-- SAML 2.0 and SCIM 2.0 support.
--
-- 1. API keys gain a scope column so the existing api_keys table can also host
--    dedicated SCIM provisioning tokens. SCIM tokens are not bound to a project,
--    so project_id becomes nullable.
--
-- 2. No SAML-specific tables are required: IdP metadata and verification credentials
--    are injected via spring.security.saml2.relyingparty.registration.* properties.

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'PROJECT';
ALTER TABLE api_keys ALTER COLUMN project_id DROP NOT NULL;

-- Help the SCIM auth interceptor look up SCIM-only keys quickly.
CREATE INDEX IF NOT EXISTS idx_api_keys_scope ON api_keys (scope);
