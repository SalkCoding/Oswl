# Changelog

## [Unreleased]

### Features

* **EPSS & KEV threat intel** — exploit probability (FIRST.org EPSS) and CISA KEV flags enriched during scan; shown on component detail CVE cards
* **Project access control** — `project_members` table, per-project membership bootstrap, IDOR checks on project-scoped routes
* **CLI manifest parse API** — `POST /api/scan/parse` (multipart zip) as CLI step 1 before `POST /api/scan`
* **OSS notices page** — `GET /oss-notices` third-party license attribution
* **VCS push webhook** — `POST /api/import/webhook` for GitHub/GitLab/Bitbucket auto re-scan; per-project webhook settings
* **Scan notifications** — Slack/Teams incoming webhooks and optional email digest on Critical CVE / restricted license findings
* **Project team management** — `GET/POST/DELETE /api/projects/{id}/members` and Projects UI “Manage team” panel
* **Security Center export** — CSV export and printable view
* **License export** — NOTICE and SPDX tag-value SBOM per scan
* **Deployment profile** — per-project AI triage profile via `PATCH /api/projects/{id}/deployment-profile`

### Fixes & security

* CSRF exemptions for CLI (`POST /api/scan`, `POST /api/scan/parse`, `GET /api/scan/ping`) and inbound VCS webhooks
* Outbound webhook SSRF guard (`OutboundUrlValidator`) on scan notifications and notification settings
* API reference aligned with implemented endpoints (removed phantom `POST /api/auth`, corrected GitHub disconnect method)

### Refactoring & quality

* `oswl-domain` module for shared enums; parser split (`ManifestTreeWalker`, `NpmPackageJsonParser`)
* Service tests: `ImportWebhookService`, `ProjectMemberService`, `ScanNotificationService` (SSRF)
* CI: `test-auth` / `test-web` jobs; JaCoCo reports across `oswl-app` + `oswl-scan-core`
* Wiki sync excludes `docs/ko/` (Korean docs remain repo-only)

### Documentation & ops

* Korean docs in `docs/ko/` (repository only; excluded from Wiki sync)
* English `docs/` synced to GitHub Wiki on `main` push (`docs/ko/` excluded)
* `CONTRIBUTING.md`, Dependabot, issue templates, CI on `develop` with parallel `testFast`/`testParser` and JaCoCo gate
* **Gradle multi-module:** `oswl-app`, `oswl-scan-core`, `oswl-vuln-client`; JUnit `@Tag` test suites (`testFast`, `testParser`, `testIntegration`, …)
* Docker Compose PostgreSQL image bumped to **18** (`postgres:18`; volume mount `/var/lib/postgresql` per PG 18+ image layout). Existing PG 15 volumes are not compatible — recreate the volume or migrate with `pg_dump` / `pg_restore`

---

## [1.0.0](https://github.com/SalkCoding/Oswl/releases/tag/v1.0.0) (2026-06-10)

### Features

* Initial OsWL release
