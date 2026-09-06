# Changelog

## Unreleased

- Fixed nested dialog focus restoration and stale global search responses after clearing or closing search.
- Include RE2/J, web-push, Bouncy Castle and jose4j in the generated version manifest, localized OSS notices and license references. Rebuild that manifest when build.gradle changes. Simplify migration verification to read the exact versioned SQL files.
- Fix Security Center duplicate bulk requests, stale filtered results after updates, accessible inline row-refresh failures/retries and detail retry focus restoration. Six RequestLifecycle browser scenarios passed; latest results across the expanded 26-scenario UI set have no failures. Record 993 backend tests (two opt-in/live skips), real image/checkout/model evidence and remaining environment-dependent checks.
- Verify real Debian and Ubuntu OCI images against OSV and allow an unrelated merged-usr directory link while rejecting a non-directory ancestor required by the selected package DB. Eight Python checks passed; immutable digests and advisory IDs are recorded.
- Verify the exact 512 MiB expanded snapshot staging boundary in a 128 MiB JVM: the boundary succeeds, one additional byte fails, and staged files are removed in both cases. Keep semantic import, concurrent export and operational retention/partition decisions separate.
- Fix opt-in JDBC session activation on Boot 4. Two real JVMs passed cross-instance login/logout, single-session enforcement and forced restart with durable H2 writes; expired job fixtures retained cancellation and terminated without duplicate scans. Two observed scheduler cycles had one owner; the one-minute lock explains skipped 20-second triggers. Actual Dgs/Express/Maui/Rails checkout parsing also passed without fixture skips. PostgreSQL/LB and remote CI remain separate.
- Add bounded OCI registry/layout inspection with manifest/layer checksums, whiteout composition, installed Alpine/Debian/Ubuntu source package collection and optional exact-version OSV queries. Seven Python checks passed; the pinned Alpine 3.16.0 image yielded ten packages and known BusyBox/musl/OpenSSL/zlib advisories without executing the image.
- Add opt-in browser alerts for new high-risk vulnerabilities and failed gates, with encrypted owned subscriptions, fresh authorization checks, persistent bounded retries, expiration cleanup and a navigation-restricted service worker. Eight service/crypto tests, SQL migration constraints, service-worker checks and four organization browser scenarios passed; live push-provider delivery remains unverified.
- Add an organization risk briefing with prioritized follow-up, coverage caveats and a printable five-project summary. Both service and controller enforce organization-view access. Three combined organization/onboarding browser scenarios passed, including three languages, narrow viewport and axe checks.
- Add an inline administrator-only teammate form to onboarding, including role selection, temporary password clearing and retryable failures; reject deleted role selections and redact temporary passwords in DTO output. Real account creation/duplicate-error browser verification passed.
- Add administrator-managed, versioned custom secret/IaC rules using RE2/J with bounded input/time/findings and explicit incomplete-scan findings. Six unit/integration tests verify no matched-secret output, invalid/expensive patterns, permission denial, stale publication and disabling rules.
- Add scoped CocoaPods Specs v2 bundles with original metadata, provenance/checksums, transactional validation and offline repository/license resolution. Four integration tests include actual Podfile ingestion, owned CVE detection, missing-spec UNKNOWN and export/import lookup equivalence. No upstream Specs dataset is redistributed.
- Verify real isolated GGUF download/checksum, runtime inference and forced-process restart; exercise server-side session and OTP expiry in browser tests. Evidence: Ui-Operations-Verification.md.
- Persist vulnerability lookup outcomes and block CI gates when analysis coverage is incomplete; retain unresolved snapshot evidence and NVD CVSS v4 metrics. Backend regression: 971 tests, 0 failures/errors, 8 opt-in skips; detection/auth browser scenarios: 4 passed.
* **verification:** Recorded UI/operations evidence and narrowed remaining roadmap work. Backend suite: 963 tests, 956 passed, 7 optional skips; subsequent OSV/enrichment: 37 passed; browser: 16 plus 1 AI scenario passed; cluster predicates: 4 passed. Added browser and pure cluster checks to CI without running remote jobs. Real providers, PostgreSQL/LB, devices, model startup and unexecuted state combinations remain explicit in [the report](docs/ko/Ui-Operations-Verification.md).
* **detection:** OSV transport failures, invalid/partial responses and absent offline data remain unresolved; they no longer create a successful fetch timestamp. Component detail distinguishes unanalysed data from zero findings in all three languages. Real parse/ingest/enrich/browser/gate fixtures cover malicious, clean, unsupported and unavailable results. Gate completeness policy and live-source coverage remain open (roadmap 27).
* **embedded AI:** Publish background download errors, clear them on retry, bound download bytes and serialize admission; interruption stops fallback and removes partial output. Five local HTTP/checksum/concurrency tests and a browser failure/retry test pass while preserving existing models. Actual GGUF download/startup remains unverified (roadmap 26).
* **cluster verification:** Validate protected identity without following redirects, add anonymous/logout controls and check duplicate scheduler execution per observed cron cycle. Four pure predicate tests and Bash syntax pass. The runtime draft remains gated until process/schema/session and PostgreSQL rehearsal are performed (roadmap 25).
* **onboarding:** Browser fixtures verify GitHub/GitLab/Bitbucket permission failures, retry and secret-input clearing, plus webhook network failure/retry. No external credentials or deliveries were used; real provider verification remains open (roadmap 24).
* **settings:** Failed initial loads cannot overwrite Reports/Webhooks/Cache with defaults; retry restores saved values and failed/overlapping saves preserve newer edits. Removed duplicate Alpine init calls and return real 403 HTML/JSON for membership and permission denials. Verified 60 language/page paths, read-only/no-permission accounts, injected 500/403/network failures and actual beforeunload (roadmap 22).
* **accessibility:** Settings use responsive headers and contained table scrolling; corrected button contrast and accessible input/filter names. All 12 settings tabs passed Tab/Escape, 390px document-width and serious/critical axe checks in Japanese Chromium. Physical devices remain unverified (roadmap 23).
* **verification:** External Dgs/Express/Maui/Rails and manifest parity checks now require readable nonempty manifests and explain missing-fixture skips. Empty checkout and valid-input controls passed; remote PR CI remains pending (roadmap 33).
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
