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

`POST /api/scan` normally requires `submitterEmail` and `submitterPassword` in the JSON body. Successful ingests are recorded in the **audit log** (`SCAN.INGEST`) with the submitter email — not in a dedicated scan-result column.

**CI machine tokens** (`ApiKeyType.MACHINE`): issue a key with `POST /api/projects/{id}/keys` and body `{ "machineToken": true, "boundUserEmail": "ci@company.com" }`. The bound user must have `SCAN_SUBMIT` and project membership. `submitterPassword` may be omitted; `submitterEmail` may be omitted if it matches the bound user.

CLI paths under `/api/scan/**` (except `GET /api/scan/{scanId}/status`) are authenticated via `Authorization: Bearer oswl_<api_key>` by `ApiKeyAuthInterceptor`. CSRF is disabled for `POST /api/scan`, `POST /api/scan/parse`, `GET /api/scan/ping`, and `POST /api/import/webhook`. See [CLI scan API security](Scan-Api-Security.md).

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
| `POST` | `/api/change-password` | Change password (forced or voluntary; session + CSRF) |
| `POST` | `/api/my/change-password` | Start self-service password change |
| `POST` | `/api/my/change-password/otp-verify` | Verify OTP for password change |
| `POST` | `/api/my/change-password/otp-resend` | Resend OTP for password change |

---

## Public Pages

| Method | Path | Description |
|---|---|---|
| `GET` | `/oss-notices` | Open-source license notices page |

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
| `GET` | `/projects/cli-integration` | `PROJECT_VIEW` | CLI integration guide page |
| `GET` | `/projects/git-integration` | `PROJECT_VIEW` | Git integration guide page |
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

## VCS Push Webhook (auto re-scan)

No session auth. Verified per-project secret. CSRF exempt. Set `OSWL_PUBLIC_BASE_URL` (or `oswl.public-base-url`) so webhook URLs are absolute.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/import/webhook` | Per-project secret | Handle GitHub / GitLab / Bitbucket push events and enqueue a re-import |

**Secret verification**

| Provider | Header / mechanism |
|---|---|
| GitHub | `X-Hub-Signature-256` (HMAC-SHA256 of body with project secret) |
| GitLab | `X-Gitlab-Token` matches project secret |
| Bitbucket / generic | `X-OsWL-Webhook-Secret` matches project secret |

Response: `{ "accepted": true/false, "message": "…", "jobId": "…" }` (optional `jobId` when a scan is queued).

### Project webhook configuration

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/projects/{id}/webhook` | `PROJECT_VIEW` + membership | Webhook URL, enabled flag (secret not returned) |
| `PUT` | `/api/projects/{id}/webhook` | `PROJECT_UPDATE` + membership | `{ "enabled", "rotateSecret" }` — new secret returned once when rotated |

---

## GitHub OAuth / PAT

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/github/connect` | Connect a GitHub PAT |
| `POST` | `/api/github/disconnect` | Remove all GitHub tokens from session |
| `GET` | `/api/github/status` | Connection status |
| `GET` | `/api/github/accounts` | List authenticated accounts |
| `GET` | `/api/github/repos` | List accessible repositories |
| `GET` | `/api/github/branches` | List branches for a repo |
| `GET` | `/api/github/branches/by-project` | List branches for a linked project |
| `GET` | `/api/github/branch-updated-at` | Last commit date for a branch |
| `POST` | `/api/github/repos/import` | Import a GitHub repo into a project |
| `DELETE` | `/api/github/accounts/{login}` | Remove a specific account |

---

## VCS Branches

Session auth. GitLab and Bitbucket branches for linked projects (GitHub uses `/api/github/branches`).

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/vcs/branches?projectId=` | List branches for a project's VCS repo |

---

## CLI Scan

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API key | Connectivity and key validity check |
| `GET` | `/api/scan/manifest-rules` | API key | Manifest file collection rules (same as `/scripts/manifest-rules.json`) |
| `POST` | `/api/scan/parse` | API key | Parse a manifest zip archive (CLI step 1) |
| `POST` | `/api/scan` | API key + submitter credentials | Submit a dependency scan (CLI step 2) |
| `GET` | `/api/scan/{scanId}/status` | Session | Poll scan status (UI; requires project membership) |

---

## Security Center

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | Security Center page |
| `GET` | `/projects/{id}/security-center/print` | `SECURITY_CENTER_EXPORT` | Printable Security Center view |
| `GET` | `/projects/{id}/security-center/export?format=csv` | `SECURITY_CENTER_EXPORT` | Export findings as CSV |
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
| `GET` | `/projects/{id}/license/export/notice` | `LICENSE_EXPORT` | Download NOTICE.txt |
| `GET` | `/projects/{id}/license/export/spdx` | `LICENSE_EXPORT` | Download SPDX SBOM (tag-value) |
| `GET` | `/projects/{id}/license/export/spdx-json` | `LICENSE_EXPORT` | Download SPDX SBOM (JSON) |
| `GET` | `/projects/{id}/license/export/cyclonedx` | `LICENSE_EXPORT` | Download CycloneDX SBOM (JSON) |

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

## Project Members

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/projects/{id}/members` | `PROJECT_VIEW` + membership | List project members |
| `POST` | `/api/projects/{id}/members` | `PROJECT_MEMBER_MANAGE` + membership | Add member by email (`{ "email", "role": "ADMIN" \| "MEMBER" }`) |
| `DELETE` | `/api/projects/{id}/members/{userId}` | `PROJECT_MEMBER_MANAGE` + membership | Remove member (last ADMIN cannot be removed) |

---

## Project API Keys

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + project membership | List project keys |
| `POST` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + project membership | Create a key. Body: optional `{ "machineToken": true, "boundUserEmail": "…" }` for CI machine tokens |
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

### Notifications

Instance-wide alert channels when scans find **Critical** CVEs or **RESTRICTED** licenses.

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/api/settings/notifications` | `SETTINGS_NOTIFICATION_MANAGE` | Slack/Teams webhook status, email digest and trigger flags |
| `PUT` | `/api/settings/notifications` | `SETTINGS_NOTIFICATION_MANAGE` | Update channels (`slackWebhookUrl`, `teamsWebhookUrl`, `clearSlackWebhook`, `clearTeamsWebhook`, `emailDigestEnabled`, `notifyCriticalCve`, `notifyLicenseViolation`) |

Webhook URLs are stored encrypted. Email digest requires SMTP configured under Security settings.

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
