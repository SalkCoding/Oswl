-- Superseded by Flyway V7__api_key_machine.sql. Kept for manual upgrades only.
-- CI machine tokens: passwordless scan submit for bound user
ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS key_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN IF NOT EXISTS bound_user_id BIGINT REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_api_keys_bound_user ON api_keys(bound_user_id);
