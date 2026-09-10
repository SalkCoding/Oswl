-- AI context-hash cache hit/miss counters (run once on PostgreSQL when ddl-auto=validate).

ALTER TABLE ai_daily_usage ADD COLUMN IF NOT EXISTS cache_hits INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_daily_usage ADD COLUMN IF NOT EXISTS cache_misses INTEGER NOT NULL DEFAULT 0;
