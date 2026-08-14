-- ROADMAP A7: extend the org/team/project policy hierarchy to cover onlyReachable and
-- failOnSecrets, the two gate options that previously bypassed it and always fell back to the
-- instance-wide default. Nullable, same as the other five gate fields: null means "not set at
-- this level, inherit from the parent scope or the instance default".

ALTER TABLE policies ADD COLUMN IF NOT EXISTS only_reachable BOOLEAN;
ALTER TABLE policies ADD COLUMN IF NOT EXISTS fail_on_secrets BOOLEAN;
