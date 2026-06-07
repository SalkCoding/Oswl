# API Reference

This page summarizes every REST endpoint exposed by OsWL. For interactive documentation with request/response schemas, use **Swagger UI** in the **`local` profile** (`http://localhost:8080/swagger-ui.html`). Swagger is **disabled in `prod`**.

---

## Authentication

### Session (Web UI)

Browser-based requests use Spring Security cookie sessions. Login via `POST /login`.

### API Key (CLI)

CLI endpoints require:

```
Authorization: Bearer oswl_<api_key>
```

### CLI Scan Submitter Credentials

`POST /api/scan` additionally requires `submitterEmail` and `submitterPassword` in the JSON body to authenticate the submitter. Successful ingests are recorded in the **audit log** (`SCAN.INGEST`) with the submitter email — not in a dedicated scan-result column.

---

## Auth Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/login` | Login page |
| `POST` | `/login` | Submit credentials |
| `GET` | `/login/otp-verify` | OTP verification page |
| `POST` | `/login/otp-verify` | Submit OTP code |
| `POST` | `/login/otp-resend` | Re-send OTP email |
| `GET` | `/setup` | Setup wizard page (first boot only) |
| `POST` | `/setup` | Submit setup wizard form |

---

## Projects

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects` | `PROJECT_VIEW` | Projects dashboard |
| `GET` | `/projects/list` | `PROJECT_VIEW` | Projects list (JSON) |
| `DELETE` | `/projects/{id}` | `PROJECT_DELETE` | Soft-delete project |
| `POST` | `/projects/{id}/restore` | `PROJECT_RESTORE` | Restore from trash |
| `DELETE` | `/projects/{id}/permanent` | `PROJECT_PERMANENT_DELETE` | Permanently delete |
| `DELETE` | `/projects/trash/all` | `PROJECT_PERMANENT_DELETE` | Empty trash |
| `DELETE` | `/projects/trash/selected` | `PROJECT_PERMANENT_DELETE` | Delete selected trash |
| `POST` | `/projects/trash/restore-selected` | `PROJECT_RESTORE` | Bulk restore |
| `GET` | `/projects/cards` | `PROJECT_VIEW` | Project card HTML fragment (dashboard) |
| `GET` | `/projects/scan-status/stream?ids=` | `PROJECT_VIEW` | **SSE** — `scan-update` when listed projects finish scanning |
| `POST` | `/projects` | `PROJECT_CREATE` | Create project (JSON) |
| `PATCH` | `/api/projects/{id}/deployment-profile` | `PROJECT_UPDATE` | Set AI deployment profile for CVE triage (`{ "deploymentProfile" }`) |

---

## Quick Import

Requires `PROJECT_CREATE` (or System Admin). Session auth.

| Method | Path | Description |
|---|---|---|
| `GET` | `/projects/quick-import` | Quick Import page |
| `GET` | `/api/quick-import/connections` | List VCS connections for the current user |
| `GET` | `/api/quick-import/repos?provider=` | List repositories from a provider (`GITHUB`, `GITLAB`, `BITBUCKET`) |
| `POST` | `/api/quick-import/start` | Enqueue a new import job (`{ "repoUrl", "branch" }` → `{ "jobId" }`) |
| `GET` | `/api/quick-import/jobs` | List all jobs for the current user (queued, running, recent) |
| `GET` | `/api/quick-import/job/{jobId}` | Poll one job (`QuickImportJobStatus`) |
| `GET` | `/api/quick-import/job/{jobId}/stream` | **SSE** — event `job-update` with JSON status (fallback: poll) |

**Job phases:** `QUEUED` → `CLONING` → `PARSING` → `SCANNING` → `ENRICHING` → `DONE` | `FAILED`.  
Up to **two** imports run concurrently (`oswl.quick-import.max-concurrent`); additional jobs wait in a FIFO queue (`queuePosition`).  
During `ENRICHING`, responses include `percent`, `subPhase` (`CVE`, `LICENSE`, `POSTURE`, `TREND`, `DIFF`), `detailLines`, and `aiPreviews`.

---

## GitHub OAuth / PAT

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/github/connect` | Connect a GitHub PAT |
| `DELETE` | `/api/github/disconnect` | Remove GitHub connection |
| `GET` | `/api/github/status` | Connection status |
| `GET` | `/api/github/accounts` | List authenticated accounts |
| `GET` | `/api/github/repos` | List accessible repositories |
| `GET` | `/api/github/branches` | List branches for a repo |
| `GET` | `/api/github/branch-updated-at` | Last commit date for a branch |
| `DELETE` | `/api/github/accounts/{login}` | Remove a specific account |

---

## CLI Scan

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth` | API key | Validate API key (legacy) |
| `GET` | `/api/scan/ping` | API key | Connectivity and key validity check |
| `POST` | `/api/scan` | API key + credentials | Submit a dependency scan |
| `GET` | `/api/scan/{scanId}/status` | Session | Poll scan status |

---

## Security Center

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | Security Center page |
| `PATCH` | `/projects/{id}/security-center/bulk-status` | `SECURITY_CENTER_UPDATE_STATUS` | Bulk CVE status update |

---

## Component Detail

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/components/{compId}` | `COMPONENT_DETAIL_VIEW` | Component detail (full page or HTMX fragment) |
| `POST` | `/projects/{id}/components/{compId}/cves/{cveDbId}/ai-summarize` | `SECURITY_CENTER_UPDATE_STATUS` | Regenerate AI triage for one CVE |
| `POST` | `/projects/{id}/components/{compId}/defer` | `SECURITY_CENTER_UPDATE_STATUS` | Record remediation deferral |
| `POST` | `/projects/{id}/components/{compId}/create-pr` | `SECURITY_CENTER_UPDATE_STATUS` | Open a VCS pull request with a dependency fix |

---

## License

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/license` | `LICENSE_VIEW` | License Analysis page |

---

## Risk Trend

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/risk-trend` | `RISK_TREND_VIEW` | Risk Trend page |

---

## Version Diff

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/version-diff` | `VERSION_DIFF_VIEW` | Version Diff page |

---

## Scan History

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/scan-history` | `SCAN_HISTORY_VIEW` | Scan History page |
| `DELETE` | `/projects/{id}/scan-history/{scanId}` | `PROJECT_DELETE` | Delete a scan record |

---

## Project API Keys

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + project membership | List project keys |
| `POST` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + project membership | Create a key |
| `DELETE` | `/api/projects/{id}/keys/{keyId}` | `SETTINGS_CLI_KEY_MANAGE` + project membership | Revoke a key |

---

## Admin

### Users

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/users` | List all users |
| `POST` | `/api/admin/users` | Create / invite a user |
| `PUT` | `/api/admin/users/{id}/roles` | Update user roles |
| `PUT` | `/api/admin/users/{id}/display-name` | Change display name |
| `PUT` | `/api/admin/users/{id}/activate` | Activate account |
| `PUT` | `/api/admin/users/{id}/deactivate` | Deactivate account |
| `DELETE` | `/api/admin/users/{id}` | Delete user |

### Role Templates

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/role-templates` | List templates |
| `POST` | `/api/admin/role-templates` | Create template |
| `GET` | `/api/admin/role-templates/permissions` | List all available permissions |
| `PUT` | `/api/admin/role-templates/{id}` | Update template |
| `DELETE` | `/api/admin/role-templates/{id}` | Delete template |

### Audit Logs

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/audit-logs` | Paginated audit log |
| `GET` | `/api/admin/audit-logs/export.csv` | Export as CSV |

### Admin CLI Keys

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/cli-keys` | List global CLI keys |
| `POST` | `/api/admin/cli-keys` | Create global key |
| `PATCH` | `/api/admin/cli-keys/{keyId}/toggle` | Enable / disable key |

---

## Settings

### Security (SMTP, 2FA, Password Policy)

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | Get security settings |
| `PUT` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | Update settings |
| `POST` | `/api/settings/security/mail/test` | `SETTINGS_SECURITY_MANAGE` | Send test email |

### AI

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | Active provider + enrichment preferences (temperature, limits, deployment default, …) |
| `PUT` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | Upsert provider credentials and/or preferences |
| `PUT` | `/api/settings/ai/deactivate` | `SETTINGS_AI_MANAGE` | Deactivate active provider (optional preference body) |
| `PUT` | `/api/settings/ai/activate/{provider}` | `SETTINGS_AI_MANAGE` | Switch active provider |
| `POST` | `/api/settings/ai/test-connection` | `SETTINGS_AI_MANAGE` | Test provider connectivity (no persist) |
| `GET` | `/api/settings/ai/prompts` | `SETTINGS_AI_MANAGE` | Editable prompt templates + overrides |
| `POST` | `/api/settings/ai/golden-test` | `SETTINGS_AI_MANAGE` | Run built-in prompt regression fixtures |

Providers: `OPENAI`, `ANTHROPIC`, `GEMINI`, `LOCAL`.

### License Policy

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/license-policy` | `LICENSE_POLICY_MANAGE` | List SPDX policy entries |
| `PUT` | `/api/settings/license-policy/{spdxId}` | `LICENSE_POLICY_MANAGE` | Update status for one license |

### VCS Connections

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | List connections |
| `POST` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | Add connection |
| `DELETE` | `/api/settings/vcs/{id}` | `SETTINGS_VCS_MANAGE` | Remove connection |

### Cache (enrichment policy)

Controls how long library CVE/license data from **deps.dev** and **OSV** is reused between scans.

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | List cache keys (`DEPS_DEV`, `OSV_VULN`) with TTL and last-cleared metadata |
| `PUT` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | Update TTL for one key (`cacheKey`, `ttlSeconds`) |
| `POST` | `/api/settings/cache/clear?cacheKey=…` | `SETTINGS_CACHE_MANAGE` | Mark cache cleared — libraries fetched before that time are re-fetched on the next enrichment |

**TTL semantics** (Settings → Cache UI maps to `DEPS_DEV` TTL used by enrichment):

| UI mode | `ttlSeconds` | Behaviour |
|---|---|---|
| Always Refresh | `1` | Re-fetch every library on every scan |
| Custom TTL | `N` (seconds) | Re-fetch when `libraries.fetched_at` is older than N |
| Permanent Cache | very large (e.g. 50 years) | Fetch each library only once |

> **Note:** The former `/api/settings/external` endpoints and `external_api_settings` table were removed. Cache is managed solely via `/api/settings/cache`.

---

## Local / Test (local profile only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/data/test` | Reset DB and seed rich test data |
| `GET` | `/data/test-api-key` | Get a usable test API key |
