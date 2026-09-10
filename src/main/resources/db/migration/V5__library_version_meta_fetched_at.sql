-- OsWL — separate TTL for deps.dev version metadata.
--
-- refreshVersionMetadata() previously called deps.dev GetVersion for every cache-hit library on
-- every scan, even though the data (isLatestVersion / deprecated / latestVersion / scorecard)
-- changes slowly. This column tracks when that metadata was last refreshed so the service can
-- skip libraries still within oswl.cache.version-meta-ttl-seconds (default 24h).
--
-- Null on every pre-existing row (idempotent, matching V2/V3/V4) — a null is treated as
-- "never refreshed", so the first scan after the migration refreshes once and then caches.

ALTER TABLE libraries ADD COLUMN IF NOT EXISTS version_meta_fetched_at TIMESTAMP;
