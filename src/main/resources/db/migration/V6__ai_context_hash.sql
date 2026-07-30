-- OsWL performance plan F1 — AI response caching (context-hash based re-call skip).
--
-- Before this, every scan unconditionally cleared and re-requested AI CVE/license summaries
-- for every candidate, even when nothing relevant had changed since the last scan (CI repeat
-- scans of an unchanged dependency tree were effectively a 100% re-call rate). These columns
-- store the hash of the fields that actually drive each prompt, so a re-scan that recomputes
-- the same hash and already has a summary can skip the AI call entirely.
--
-- Null on every pre-existing row (idempotent, matching V2-V5) — a null hash never matches a
-- freshly computed one, so every existing summary is simply treated as a cache miss once and
-- then cached from then on.

ALTER TABLE library_cves ADD COLUMN IF NOT EXISTS ai_context_hash VARCHAR(64);
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS ai_license_context_hash VARCHAR(64);
