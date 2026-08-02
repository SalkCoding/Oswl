# Administration

This page covers all admin-only features: user management, role templates, audit logs, security settings, and SMTP configuration.

> All actions on this page require **System Admin** privileges unless noted otherwise.

Role templates here control **instance-wide permissions**, not which projects a user can open. See [Authorization layers](Authorization-Layers.md).

---

## User Management

**Settings → Admin → Users**

### Inviting a User

1. Click **Invite User**.
2. Enter the user's **email** and **display name**.
3. Assign one or more **role templates**.
4. Click **Send Invite** (or **Create** if email is disabled — a temporary password is generated).

The user receives an email with a temporary password and is forced to change it on first login.

### Editing a User

| Action | Endpoint |
|---|---|
| Change display name | `PUT /api/admin/users/{id}/display-name` |
| Update roles | `PUT /api/admin/users/{id}/roles` |
| Activate account | `PUT /api/admin/users/{id}/activate` |
| Deactivate account | `PUT /api/admin/users/{id}/deactivate` |
| Delete user | `DELETE /api/admin/users/{id}` |

> Deactivated users cannot log in but their data (audit logs, scan attributions) is preserved.

### Self-service account deletion

Any authenticated user (except the **system administrator**) can delete their own account from the user menu (**Delete account**), after confirming their current password.

| Item | Behaviour |
|---|---|
| Endpoint | `POST /api/my/delete-account` — user id is taken **only** from the session principal (no path/body user id) |
| System admin | Cannot self-delete |
| Removed | `users` row, `project_members` rows, stored VCS tokens |
| Preserved | All prior **audit log** rows (actor email/name/id snapshots), projects, scans, import history |
| Audit action | `USER.SELF_DELETE` — logged **before** the user row is deleted so `actor_user_id` and display name are captured |

Admin-initiated deletion remains `USER.DELETE` via `DELETE /api/admin/users/{id}`.

---

## Role Templates

**Settings → Admin → Role Templates**

A Role Template is a named bundle of permissions that can be assigned to multiple users.

### Built-in role templates

On the **first startup with an empty database**, OsWL creates three templates you can edit:

| Template | Intended audience |
|----------|-------------------|
| **Admin** | Full permission catalog (instance operators) |
| **Developer** | Scan, triage, license view/export, VCS and CLI keys |
| **Viewer** | Read-only analysis pages and exports |

These are **role templates** (Layer A). They do not automatically add users to every project — see [Authorization layers](Authorization-Layers.md).

You can create additional templates or change permissions at any time.

### Permissions Reference

| Permission | Description |
|---|---|
| `PROJECT_VIEW` | View the project list and project details |
| `PROJECT_CREATE` | Register new projects (Quick Import or CLI) |
| `PROJECT_DELETE` | Move projects to trash |
| `PROJECT_RESTORE` | Restore trashed projects |
| `PROJECT_PERMANENT_DELETE` | Permanently delete projects from trash |
| `SCAN_SUBMIT` | Submit scans via CLI (`POST /api/scan`) |
| `SCAN_VIEW` | View scan results |
| `SCAN_HISTORY_VIEW` | View the scan history list |
| `SECURITY_CENTER_VIEW` | View the Security Center CVE list |
| `SECURITY_CENTER_UPDATE_STATUS` | Update CVE triage status |
| `SECURITY_CENTER_EXPORT` | Export Security Center results |
| `LICENSE_VIEW` | View the License Analysis page |
| `LICENSE_EXPORT` | Download NOTICE and SPDX SBOM files |
| `LICENSE_POLICY_MANAGE` | Add / edit / remove license policy entries |
| `SCAN_HISTORY_DELETE` | Delete entries from scan history |
| `COMPONENT_DETAIL_VIEW` | View the Component Detail panel |
| `VERSION_DIFF_VIEW` | View Version Diff |
| `RISK_TREND_VIEW` | View Risk Trend charts |
| `SETTINGS_AI_MANAGE` | Configure AI provider settings |
| `SETTINGS_VCS_MANAGE` | Add / remove VCS connections |
| `SETTINGS_CLI_KEY_MANAGE` | Manage project CLI API keys |
| `SETTINGS_CACHE_MANAGE` | Manage cache settings |
| `SETTINGS_SECURITY_MANAGE` | Configure SMTP and 2FA settings |
| `ORG_DASHBOARD_VIEW` | **v1.0.4** — View the organization dashboard (`/org-dashboard`) |
| `AUDIT_LOG_VIEW` | **v1.0.4** — View the audit log |
| `AUDIT_LOG_EXPORT` | **v1.0.4** — Export the audit log for SIEM ingestion |
| `SETTINGS_JIRA_MANAGE` | **v1.0.4** — Manage the Jira integration |
| `SETTINGS_SNAPSHOT_MANAGE` | **v1.0.4** — Manage offline snapshot bundles |

> The five v1.0.4 permissions are **not** added to existing role templates, so they start ungranted. Grant them deliberately — `AUDIT_LOG_EXPORT` in particular sends audit records off the platform.

### Creating a Template

1. Click **New Role Template**.
2. Enter a name (e.g. "Developer", "Security Analyst", "Read Only").
3. Check the desired permissions.
4. Click **Save**.

---

## Security Settings (SMTP and 2FA)

**Settings → Security**

### SMTP (Mail Server)

OsWL uses SMTP to send OTP emails for two-factor authentication and user invitations.

| Field | Description |
|---|---|
| **Mail Mode** | `DISABLED` (no mail), `SMTP` (standard relay), `STARTTLS` / `SSL_TLS` |
| **Host** | SMTP server hostname |
| **Port** | SMTP port (typically 25, 465, or 587) |
| **Username / Password** | SMTP credentials (password stored encrypted at rest) |
| **Sender Name / Address** | The "From" display name and address |

Click **Send Test Email** to verify the configuration before saving.

### Two-Factor Authentication (2FA)

| Mode | Behavior |
|---|---|
| `DISABLED` | No OTP step — users log in with email + password only |
| `OPTIONAL` | OTP is available but users can skip it |
| `REQUIRED` | All users must complete the OTP step on every login |

#### Trusted Devices

When 2FA is enabled, users can mark a browser as **trusted** after a successful OTP verification. Trusted devices skip the OTP step for a configurable period (default: 30 days).

### Password Policy

| Setting | Default | Description |
|---|---|---|
| Minimum Password Length | `8` | Enforced on invite creation and password change |

---

## Server Properties (application.yaml)

Instance-level security flags set in `application.yaml` or via environment variable (Spring relaxed binding). They are not editable from the Settings UI; a restart is required.

| Config key | Env var | Default | Description |
|---|---|---|---|
| `oswl.quick-import.allow-build-exec` | `OSWL_QUICK_IMPORT_ALLOW_BUILD_EXEC` | `false` | When `false`, Quick Import parses manifests **statically** and never executes build tooling found in the cloned repository (`mvnw`, `gradlew`, `dotnet`). Set to `true` only when every importable repository is trusted — build-based version resolution runs repository build scripts on the OsWL host. |
| `oswl.security.trusted-proxies` | `OSWL_SECURITY_TRUSTED_PROXIES` | *(empty)* | Comma-separated IPs of trusted reverse proxies. The `X-Forwarded-For` header is honored for client-IP resolution (audit logs, rate limiting) only when the direct peer is in this list; when empty, the header is ignored. Set this only when OsWL runs behind a proxy you control. |
| `oswl.timezone` | `OSWL_TIMEZONE` | `Asia/Seoul` | Timezone used for time-sensitive features (currently AI usage tracking — the "day" a call is billed to). Change only if the deployment's business day should follow a different zone than the default. |

---

## Audit Log

**Settings → Admin → Audit Logs**

The audit log records every significant user and system action.

| Column | Description |
|---|---|
| **Timestamp** | When the event occurred |
| **Actor** | User email or `SYSTEM` |
| **Action** | Event code (e.g. `SCAN.INGEST`, `AUTH.LOGIN_SUCCESS`, `LICENSE.EXPORT`) |
| **Resource Type** | Entity affected (PROJECT, SCAN, USER, …) |
| **Resource ID** | ID of the affected entity |
| **Detail** | Additional context (new value, version string, etc.) |

### Filtering

Filter by actor, action (grouped in the UI — includes auth, users, projects, scans, CLI keys, components, and settings), and date range.

**User action codes** include `USER.SELF_DELETE` (self-service account deletion) and `USER.DELETE` (admin deletion).

### Export

Click **Export CSV** to download the current filtered view as a CSV file.

**SIEM export (v1.0.4)** — `GET /api/admin/audit-logs/export?format=jsonl|cef` streams the same filtered view in a SIEM-ingestible format (JSON Lines by default, or ArcSight CEF). Requires the `AUDIT_LOG_EXPORT` permission; the export itself is recorded as `AUDIT_LOG.EXPORT`.

The v1.0.4 action codes are grouped in the filter UI as **Monitoring** (`MONITOR.*`), **Integration** (`JIRA.SETTINGS_UPDATE`), **Administration** (`ORG_DASHBOARD.VIEW`, `AUDIT_LOG.EXPORT`, `SNAPSHOT.IMPORT` / `SNAPSHOT.EXPORT` / `SNAPSHOT.WANTED_LIST_EXPORT`), and **Cache** (`CACHE.UPDATE_TTL`, `CACHE.CLEAR`), alongside the new export and gate codes (`SBOM.EXPORT`, `VEX.EXPORT`, `SARIF.EXPORT`, `COMPLIANCE_REPORT.VIEW`, `GATE.EVALUATE`, `GATE.GITHUB_PUBLISH`, `PROJECT.BATCH_PR`, `SBOM.IMPORT`, `COMPONENT.JIRA_TICKET`).

### Retention

Audit records older than the configured retention period are automatically deleted by a scheduled job.

| Config key | Default | Description |
|---|---|---|
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | Records older than this many months are auto-deleted |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | Max records per API page |

---

## Organization Dashboard (v1.0.4)

`/org-dashboard` rolls all projects into one portfolio view — severity totals, worst-project ranking, KEV-listed CVE count, and licence warnings.

Requires `ORG_DASHBOARD_VIEW` (or `SYSTEM_ADMIN`). Once granted, the entry point appears in the top bar of the projects, project-detail, and version-diff screens.

---

## Monitoring endpoints (v1.0.4)

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Liveness / readiness |
| `/actuator/info` | Build and version info |
| `/actuator/prometheus` | Micrometer metrics for Prometheus scraping |

All three are admin-gated. Prometheus scrape config lives in `application-prod.yaml` under `management`.

---

## Offline snapshot bundles (v1.0.4)

**Settings → Admin → Offline Snapshot**

For air-gapped deployments (`OSWL_AIRGAPPED_ENABLED=true`), vulnerability and threat-intel data (OSV, deps.dev, FIRST.org EPSS, CISA KEV) is served from an imported snapshot rather than live APIs — no outbound HTTP is attempted, and components absent from the snapshot resolve as "no data".

`SYSTEM_ADMIN` or `SETTINGS_SNAPSHOT_MANAGE` required. See [What's New in v1.0.4](Whats-New-v1.0.4.md).

Bundles are v2 format: each JSONL file is checksummed in `meta.json`, and per-source provenance (`bundleId`, `builtAt`, `asOf`, `origin`) is stored. A checksum mismatch is rejected before any data is written.

| Action | Endpoint |
|---|---|
| Bundle status | `GET /api/admin/snapshot` |
| Import (file upload) | `POST /api/admin/snapshot/import` (multipart, optional `?mode=replace\|merge`) |
| Import (server path) | `POST /api/admin/snapshot/import-from-path` |
| Export | `GET /api/admin/snapshot/export` |
| Wanted-list | `GET /api/admin/snapshot/wanted-list` |

Imports are streamed (the upload is never buffered whole in memory) and audited as `SNAPSHOT.IMPORT`. On an air-gapped instance the in-memory KEV catalog is reloaded right after an import, so the new snapshot applies immediately.

### Definition status & staleness

The **Definition Status** card lists one row per source — `OSV`, `deps.dev (versions)`, `deps.dev (advisories)`, `FIRST.org EPSS`, `CISA KEV` — with record count, upstream **as-of** date, origin, and import time. The freshness badge next to the title is computed from the **oldest** source as-of date (not the bundle build or import time):

| Badge | Meaning |
|---|---|
| Up to date (green) | Oldest definition is within `staleness-warn-days` |
| Update recommended (amber) | Older than `staleness-warn-days` |
| Stale (red) | Older than `staleness-critical-days` |

| Config key | Env var | Default | Description |
|---|---|---|---|
| `oswl.airgapped.staleness-warn-days` | `OSWL_AIRGAPPED_STALENESS_WARN_DAYS` | `7` | Days since the oldest definition before the badge turns amber |
| `oswl.airgapped.staleness-critical-days` | `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS` | `30` | Days before the badge turns red |

An **Unresolved (no upstream data)** row appears when the builder was asked for components (via the wanted-list) it could not find or confidently evaluate upstream — treat those components as "no data", not "confirmed clean".

### Import modes

- **Bundle default** — v2 bundles built as deltas import as merge; everything else imports as replace.
- **Replace** — clears each source present in the bundle before writing.
- **Merge** — upserts by key and honors deletion markers, so a delta bundle can also remove revoked entries.

Besides file upload, **Import from Path** reads a bundle already on the server's disk. It only accepts files under the directory set in `oswl.airgapped.import-dir` (`OSWL_AIRGAPPED_IMPORT_DIR`), enforced by a realpath check; a blank value disables the endpoint entirely.

### Wanted-list (build definitions for this instance)

**Download wanted-list** exports every distinct (ecosystem, name, version) this instance has scanned as JSONL, so the `oswl-vdb` builder on an internet-connected machine fetches definitions for exactly those components instead of a full upstream mirror. The file contains only ecosystem/name/version — no project names, repository URLs, or file paths. On the connected machine, run:

```bash
scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --sources osv,epss,kev,depsdev --out bundle.zip
```

(`oswl-vdb.ps1` on Windows), then upload the resulting `bundle.zip` in the Import card. The same script also offers `verify` and `inspect` for checking a bundle before import.

---

## AI Settings

**Settings → AI**

Configure the LLM provider and enrichment behaviour for CVE/license summaries.

| Provider | Notes |
|---|---|
| **Disabled** | No AI insights generated |
| **OpenAI** | API key + model (e.g. `gpt-5.6-terra`) |
| **Anthropic** | API key + model (e.g. `claude-opus-5`); does not accept a `temperature` override — current Claude models reject sampling parameters, so response style is steered by the prompt instead |
| **Gemini** | API key + OpenAI-compatible base URL when required (e.g. `gemini-3.1-pro`) |
| **Local** | OpenAI-compatible endpoint (e.g. Ollama, with popular model tags like `qwen3`, `gemma3`, `deepseek-r1` suggested in the dropdown) — or the built-in Embedded AI sidecar below |

Each provider's model field is a free-text combo box: the dropdown lists current models as suggestions, but any model ID your account has access to can be typed in directly.

The **Embedded AI (built-in local model)** card on the same tab runs a bundled llama.cpp `llama-server` sidecar (CPU-only, localhost-only, no API key) and registers it as the LOCAL provider. The default bundled model is **Qwen3 1.7B** (downloaded on first use); the card also offers a **model dropdown** for any `.gguf` you place in the folder, a **folder override** with Save (persisted; changing it while running stops the sidecar), and **automatic fallback** to the next available model when the first choice fails to start. See [Embedded AI](Embedded-AI.md).

Only one provider is **active** at a time. The tab also exposes:

| Setting | Purpose |
|---|---|
| Prompt locale (`en` / `ko` / `ja`) | Chooses `prompts.properties` vs the Korean or Japanese overlay |
| CVE / license batch limits & severities | Caps enrichment AI calls per scan |
| Temperature / max tokens / daily call cap | LLM behaviour and cost guardrails (temperature is ignored for Anthropic — see above) |
| Default deployment profile | Context for CVE triage **and** license-risk assessment when a project has no profile — obligations differ sharply between an internal tool, a network service, and distributed software |
| Prompt overrides | Per-key template edits (see `GET /api/settings/ai/prompts`) |

**Test Connection** does not spend any tokens: it lists the provider's available models (`GET {base}/models` for OpenAI/Gemini/Ollama, `GET /v1/models` for Anthropic) rather than sending a completion, so checking credentials and reachability is free and does not count against the daily call cap. If the configured model ID is not among the models the account can access, the test still succeeds but shows a warning so a typo or an unpulled local model surfaces immediately instead of on the next scan.

**API:** `GET|PUT /api/settings/ai`, `POST /api/settings/ai/test-connection`, `POST /api/settings/ai/golden-test`.  
**Embedded AI:** `GET /api/settings/ai/embedded`, `POST .../embedded/start?model=`, `POST .../embedded/stop`, `PUT .../embedded/config` — see [API Reference — AI](API-Reference.md#ai).  
**Per project:** `PATCH /api/projects/{id}/deployment-profile`.  
**Component detail:** `POST .../cves/{cveDbId}/ai-summarize` to refresh a CVE AI summary (logged as `COMPONENT.CVE_AI_REGENERATE`).

### AI response caching (v1.0.4)

CVE and license batch enrichment stores a SHA-256 context hash on each `Cve` and `Library` row. On the next scan, if the inputs that determine the answer — severity, CVSS score/vector, fix version, EPSS bucket, KEV status, dependency type, patchability, license name/status, deployment profile, and so on — have not changed, the existing AI summary is reused and the provider is not called again. This is automatic; there is no separate admin control to clear AI response caches. The manual **Regenerate** action on the component-detail CVE bypasses the cache.

### Usage & Cost Tracking

The AI card shows today's call count, token totals, and estimated cost (`GET /api/settings/ai/usage`, backed by a daily aggregate table so the totals stay cheap to query as history grows), plus a **Recent calls** table paginated 10 rows at a time (`GET /api/settings/ai/usage/events`). Only the most recent **100** raw call events are kept — older ones are dropped (FIFO) once a new call is recorded, but the daily totals and the 7-day trend are unaffected since they come from the aggregate table, not the raw event log.

Estimated cost is a rough figure — it is **not** an invoice from the provider. It is computed **per model**, using each model's published list price per million input/output tokens (e.g. `claude-opus-5`, `gpt-5.6-terra`, `gemini-3.1-pro` each have their own rate; a model within a provider's line-up that costs 10x more than another is no longer averaged into a single flat rate). Cached-input, batch, and long-context discounts are not applied, so treat the figure as a guide. For a model with no published price (a custom deployment, a brand-new release, or anything running on the **Local** provider), estimation falls back to a flat per-provider rate:

| Config key | Default (USD / 1M tokens) |
|---|---|
| `oswl.ai.pricing.openai-input-per-1m` / `openai-output-per-1m` | `2.50` / `10.00` |
| `oswl.ai.pricing.anthropic-input-per-1m` / `anthropic-output-per-1m` | `3.00` / `15.00` |
| `oswl.ai.pricing.gemini-input-per-1m` / `gemini-output-per-1m` | `1.25` / `5.00` |
| `oswl.ai.pricing.local-input-per-1m` / `local-output-per-1m` | `0` / `0` |

Update these to match your actual contracted rates if they drift from the defaults above. The **Local** provider is always estimated at its configured rate (`0` by default) regardless of which model name it reports, since a self-hosted model has no per-token cost to look up.

---

## Cache Settings

**Settings → Cache**

Single control point for **library enrichment cache** (deps.dev + OSV). There is no separate “external API settings” screen or API.

| Cache key | Default TTL | Used for |
|---|---|---|
| `DEPS_DEV` | 7 days | Primary enrichment cache policy (version info, advisories, refetch decisions) |
| `OSV_VULN` | 7 days | Tracked alongside deps.dev; clear timestamps participate in “last cleared” logic |

| Action | API | Description |
|---|---|---|
| **View** | `GET /api/settings/cache` | TTL per key, who cleared last, when |
| **Update TTL** | `PUT /api/settings/cache` | Set `cacheKey` + `ttlSeconds` (UI: Always Refresh / Custom / Permanent) |
| **Clear** | `POST /api/settings/cache/clear?cacheKey=…` | Libraries fetched on or before the clear time are treated as stale on the next scan. Use `cacheKey=ALL` to clear every registered cache at once. |

Changes are audited as `CACHE.UPDATE_TTL` and `CACHE.CLEAR`.


---

## SAML 2.0 SSO and SCIM 2.0 Provisioning

OsWL supports SAML 2.0 single sign-on for enterprises that use Okta, Entra ID, or on-premises AD FS. When a SAML IdP is configured, the **Sign in with SSO** option appears on `/login`.

### SAML setup

1. Generate an SP signing key pair (optional but recommended):
   ```bash
   openssl req -x509 -newkey rsa:2048 -keyout oswl-saml-sp.key -out oswl-saml-sp.crt -nodes -days 3650 -subj "/CN=oswl"
   ```
2. Uncomment the SAML block in `application-prod.yaml` and set the environment variables:
   | Env var | Purpose |
   |---|---|
   | `OSWL_SAML_IDP_METADATA_URL` | IdP metadata URL (e.g. Okta/Entra app metadata) |
   | `OSWL_SAML_IDP_CERTIFICATE` | Path to the IdP signing certificate file |
   | `OSWL_SAML_SP_PRIVATE_KEY` | Path to the SP private key file |
   | `OSWL_SAML_SP_CERTIFICATE` | Path to the SP certificate file |
3. Register the SP metadata with your IdP. The metadata endpoint is:
   ```
   https://<your-oswl-host>/saml2/service-provider-metadata/oswl
   ```
4. Ensure the IdP releases an email claim (NameID or `email`/`mail` attribute).

> SAML logins skip the email OTP step because the IdP has already authenticated the user. If the email does not match an existing OsWL account, a disabled local account is created automatically so SCIM can activate and assign roles.

### SCIM 2.0 provisioning

SCIM keeps OsWL in sync with your identity provider's user lifecycle.

| Resource | Endpoint | Notes |
|---|---|---|
| Users | `/scim/v2/Users` | GET/POST/PUT/PATCH/DELETE |
| Groups | `/scim/v2/Groups` | GET/POST/PUT/PATCH/DELETE |

**Authentication:** every SCIM request must include `Authorization: Bearer <scim_token>`. Issue a dedicated SCIM token programmatically via `ApiKeyService#issueScimToken`. SCIM tokens are stored in the same `api_keys` table but have scope `SCIM`; they are rejected by the normal CLI scan API.

**Group mapping:** configure `oswl.scim.group-mapping` (env: `OSWL_SCIM_GROUP_MAPPING`) to choose how SCIM groups are represented:
- `TEAM` (default) — each SCIM group becomes a Team; members become TeamMember rows.- `ROLE_TEMPLATE` — each SCIM group becomes a RoleTemplate; members are assigned that role template.

**User deactivation:** `DELETE /scim/v2/Users/{id}` sets `active=false` in OsWL. Users are never physically deleted via SCIM, preserving audit attribution.

**Audit actions:** SCIM operations are recorded as `SCIM.USER_CREATE`, `SCIM.USER_UPDATE`, `SCIM.USER_DEACTIVATE`, `SCIM.GROUP_CREATE`, `SCIM.GROUP_UPDATE`, `SCIM.GROUP_DELETE`, `SCIM.GROUP_MEMBER_ADD`, `SCIM.GROUP_MEMBER_REMOVE`, `SCIM.AUTH_FAILURE`, and `SCIM_KEY.CREATE`. SAML login events are recorded as `SAML.LOGIN_SUCCESS` and `SAML.LOGIN_FAILURE`.
