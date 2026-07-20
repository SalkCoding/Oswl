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

### Retention

Audit records older than the configured retention period are automatically deleted by a scheduled job.

| Config key | Default | Description |
|---|---|---|
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | Records older than this many months are auto-deleted |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | Max records per API page |

---

## AI Settings

**Settings → AI**

Configure the LLM provider and enrichment behaviour for CVE/license summaries.

| Provider | Notes |
|---|---|
| **Disabled** | No AI insights generated |
| **OpenAI** | API key + model (e.g. `gpt-4o-mini`) |
| **Anthropic** | API key + model |
| **Gemini** | API key + OpenAI-compatible base URL when required |
| **Local** | OpenAI-compatible endpoint (e.g. Ollama) — or the built-in Embedded AI sidecar below |

The **Embedded AI (built-in local model)** card on the same tab runs a bundled llama.cpp `llama-server` sidecar (CPU-only, localhost-only, no API key) and registers it as the LOCAL provider. The card offers a **model dropdown** (any `.gguf` in the folder, or Auto preference order), a **folder override** with Save (persisted; changing it while running stops the sidecar), and **automatic fallback** to the next available model when the first choice fails to start. See [Embedded AI](Embedded-AI.md).

Only one provider is **active** at a time. The tab also exposes:

| Setting | Purpose |
|---|---|
| Prompt locale (`en` / `ko`) | Chooses `prompts.properties` vs Korean overlay |
| CVE / license batch limits & severities | Caps enrichment AI calls per scan |
| Temperature / max tokens / daily call cap | LLM behaviour and cost guardrails |
| Default deployment profile | Context for CVE triage when a project has no profile |
| Prompt overrides | Per-key template edits (see `GET /api/settings/ai/prompts`) |

**API:** `GET|PUT /api/settings/ai`, `POST /api/settings/ai/test-connection`, `POST /api/settings/ai/golden-test`.  
**Embedded AI:** `GET /api/settings/ai/embedded`, `POST .../embedded/start?model=`, `POST .../embedded/stop`, `PUT .../embedded/config` — see [API Reference — AI](API-Reference.md#ai).  
**Per project:** `PATCH /api/projects/{id}/deployment-profile`.  
**Component detail:** `POST .../cves/{cveDbId}/ai-summarize` to refresh a CVE AI summary (logged as `COMPONENT.CVE_AI_REGENERATE`).

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
| **Clear** | `POST /api/settings/cache/clear?cacheKey=…` | Libraries fetched on or before the clear time are treated as stale on the next scan |

Changes are audited as `CACHE.UPDATE_TTL` and `CACHE.CLEAR`.

