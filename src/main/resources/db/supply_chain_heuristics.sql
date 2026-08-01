-- Supply-chain heuristics (roadmap #16): typosquat / dependency-confusion flags on libraries
-- (run once on PostgreSQL when ddl-auto=validate; local H2 applies this automatically via ddl-auto=update).
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS typosquat_risk BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS typosquat_reason VARCHAR(300);
