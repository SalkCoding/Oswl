# Changelog

## Unreleased

* **verification:** Added opt-in H2 5k/50k-component and 100-project JDBC/heap/HTML/DOM measurements, actual bound EXPLAIN ANALYZE capture, and an isolated 2 GiB Gradle verification budget. Final targeted backend 76 and UI 9 tests passed; PostgreSQL plans and operational worst cases remain open. See [measurement methods, raw evidence and limits](docs/ko/Performance-Verification.md) (roadmap 17–21).
* **settings:** Extracted eleven settings scripts, removed duplicate Alpine initialization and corrected settings/Quick Import script order. Fixed admin/AI contrast findings. Verified 11-tab request/DOM/heap measurements, 1,100-row repeated load-more, 5k/50k repository filtering, 100 scan versions, mobile keyboard/axe and existing UI flows (9 UI scenarios, roadmap 21).
* **imports:** Concurrent creation of the same new library no longer reuses a failed JPA persistence context. A two-transaction regression and real clone/parse/ingest bursts completed 20/20, 50/50 and 100/100; delayed two-user admission/cancellation completed 7 and canceled 2 of 9 accepted jobs. Upstream outages and process restart remain unverified (roadmap 20).
* **performance:** Archive paths are fetched/deleted by scan; snapshot uploads are staged and checksum-validated before a bounded atomic transaction, with 500-row flush/detach and keyset export. Tests cover ordered dependency paths, 5,000-row import, interruption/checksum/DB-failure rollback and temporary-file cleanup. Maximum payload/concurrent export budgets remain open (roadmap 19).
* **performance:** Version Diff now reads coordinate/severity projections; Org Dashboard uses batch summaries and archived counters. Verified duplicate-component and archive equivalence, zero component/CVE graph loads, and 2/10/100-scan measurements (roadmap 18).

## [1.0.3](https://github.com/SalkCoding/Oswl/releases/tag/v1.0.3) (2026-07-19)

User-feedback release — applies the remaining findings from the 5-person usability test

### Bug Fixes

* **release:** v1.0.2 would not start on macOS/Linux — `scripts/check-java.sh` had CRLF line endings, so the bootRun JDK preflight always failed. Converted to LF and enforced via `.gitattributes`; the preflight also no longer blocks startup when its runner (bash/powershell) is unavailable
* **security-center:** severity sort comparator was inverted for High/Low (chained `.reversed()` bug) — component list now truly sorts Critical → High → Medium → Low
* **versions:** Risk Trend, Security Center, License, and Version Diff now order scans by semantic version instead of import time (importing 1.0.2 before 1.0.1 no longer reverses the timeline)
* **defer:** custom expiry dates are validated — past dates and malformed input are rejected with a 400 instead of silently becoming "+1 month"; the date field is now a native date picker with a min of tomorrow
* **ai:** insight parsing no longer leaks raw JSON fragments (`["…"]`) into the UI — responses are unwrapped with a real JSON parser, all plain-text AI paths are sanitized centrally, and system prompts explicitly forbid JSON for free-text answers
* **ai:** insight backfill after connecting a provider actually runs — it previously raced the settings transaction and silently no-oped; it now fires after commit
* **ai:** the license-insight refresh button reports failures instead of doing nothing, and a failed refresh no longer wipes existing insights
* **ai:** switching the prompt language (EN ↔ KO) regenerates recent scan insights in the new language
* **quick-import:** importing the same repository twice while a job is still running is rejected (409) instead of racing a concurrent scan

### Features

* **embedded-ai:** built-in local LLM via a llama.cpp `llama-server` sidecar — Settings → AI → Local shows an Embedded AI card that starts/stops the server and registers it as the LOCAL provider (default model Qwen3 1.7B Q4_K_M, low-spec fallback Gemma 3 1B; reasoning disabled for fast short completions; CPU-only, fully offline)
* **quick-import:** cancel button for queued and running imports
* **security-center:** sort selector (risk / name / license risk) on the component list
* **security-center:** owl reminder banner when a component's deferral period has expired (new `deferral_expired_at` column, see `db/defer_expiry_reminder.sql`)
* **apply-patch:** success panel now shows a prominent "View PR/MR" button and a collapsible "What changed?" summary with the patched manifest path and version bump
* **i18n:** browser language now decides the initial locale (Korean browsers start in Korean); added 20 previously missing Korean translations

### UX

* clearer guidance: VCS-token requirement before Apply Patch, "no account needed" hint on Quick Import, token security note in VCS settings, AI backfill notice
* graph readability: totals labeled ("N vulnerabilities found"), taller bars, larger legend text; license page and user-menu font sizes/weights bumped
* filter sidebar is compact and scrolls independently of the component list (scroll no longer bleeds to the page)
* responsive paddings on all inner pages (settings, scan history, version diff, projects, security center)

## [1.0.0](https://github.com/SalkCoding/Oswl/releases/tag/v1.0.0) (2026-06-10)

### Features

* Initial OsWL release
