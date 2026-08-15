-- OsWL v1.0.4 — decouple AI enrichment from scan completion.
--
-- A scan previously stayed ANALYZING until AI summaries finished, even though the CVE/license
-- data pipeline itself was already done. This column tracks AI progress independently so a scan
-- can reach COMPLETED as soon as its data is ready; AI enrichment continues in the background.
--
-- Null on every row that exists before this migration runs (idempotent, matching V2/V3) — the
-- application always reads this through ScanResult.getAiStatus(), which treats null as
-- NOT_APPLICABLE.

ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS ai_status VARCHAR(20);
