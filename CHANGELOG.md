# Changelog

## [1.0.5.1] - 2026-09-08

### Improvements

- Restore the original light appearance across login, search and settings. Clarify input validation, save results and unsaved changes in English, Korean and Japanese.
- Restore CLI API key deletion, correct trash visibility and restoration, and provide direct NVD links beside CVE identifiers.
- Select known OSV fixes for the installed numeric release interval; resolve inherited Maven properties and supported Gradle rich version constraints. Preserve and refresh available license metadata.
- Improve scan retry isolation, incomplete-analysis reporting, request cancellation and duplicate-action handling.
- Add opt-in browser security notifications, custom scan rules, organization risk briefings and scoped offline CocoaPods data. Add bounded OCI image inspection tooling.
- Improve diagnostic status reporting and disk-space readability. Clarify offline snapshot import, configuration transfer and scan archive exports, including empty exports.

### Upgrading

- Java 25 is required. Back up the database and persistent files before upgrading; follow the deployment and migration instructions. Migrated databases require the migrations through V35.
- Browser notifications require web push/VAPID configuration and browser permission. SMTP, VCS and AI connections must be configured for the relevant integrations.
- Existing scan records are not rewritten by a code upgrade. Rescan to apply parsing and enrichment corrections.
- A missing fix version does not prove that no patch exists. Complex ranges, ecosystem-specific ordering, incomplete source data and offline parity still require review.

See [English](docs/en/Whats-New-v1.0.5.1.md), [한국어](docs/ko/Whats-New-v1.0.5.1.md) or [日本語](docs/ja/Whats-New-v1.0.5.1.md) for release guidance.

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
