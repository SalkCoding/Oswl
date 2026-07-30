-- AI reasoning effort + explicit opt-in for regenerating insights on already-completed scans.
--
-- Both columns are null on every pre-existing row (idempotent, matching V2-V7) and are read
-- null-safely by AiPreferences:
-- - reasoning_effort IS NULL  -> AiEffort.DEFAULT, i.e. no effort parameter is sent to any
--   provider, which is exactly the behaviour before this column existed.
-- - auto_backfill_insights IS NULL -> false, so an upgrade never starts regenerating insights
--   for existing scans on its own. The user opts in from Settings > AI, or triggers a one-off
--   pass via POST /api/settings/ai/backfill.

ALTER TABLE ai_preferences ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(20);

ALTER TABLE ai_preferences ADD COLUMN IF NOT EXISTS auto_backfill_insights BOOLEAN;
