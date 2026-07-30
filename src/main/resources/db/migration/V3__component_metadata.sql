-- OsWL v1.0.4 — component identity metadata.
--
-- The component detail page could previously only restate facts already shown in the badges
-- above it ("X is included in this project. License: Apache-2.0"). These columns carry what
-- the component actually *is* — the upstream project blurb, its homepage and its source repo —
-- so the description can answer "what is this library and what is it for?".
--
-- Sourced from the deps.dev project record that is already fetched for the OpenSSF Scorecard,
-- so populating them costs no additional API calls.
--
-- All statements are idempotent (IF NOT EXISTS), matching V2.

ALTER TABLE libraries ADD COLUMN IF NOT EXISTS description      TEXT;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS homepage         VARCHAR(500);
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS source_repo_url  VARCHAR(500);
