# Administration

[What's new in 1.0.5.1](Whats-New-v1.0.5.1.md)

This page covers all admin-only features: user management, role templates, audit logs, security settings, and SMTP configuration.

> All actions on this page require **System Admin** privileges unless noted otherwise.

Role templates here control **instance-wide permissions**, not which projects a user can open. See [Authorization layers](Authorization-Layers.md).

---

## Preserved scan assessments

Component details also display preserved findings, licenses and lookup evidence. Invalid assessments or missing library identities fail rather than substituting live findings. Each unconfirmed CPE finding is marked for review. Current upgrade recommendations and verified PR actions remain available separately from historic findings. AI commentary is attached only when the current finding evidence exactly matches the preserved finding; a historical identifier does not imply a current database row. Review/deferral, package metadata and environmental scoring context remain current, as indicated on the page.

Security Center CSV exports use the selected scan's preserved assessment for inventory, severity counts, CPE review candidates, patchability, licenses and lookup evidence. Later shared-library updates do not replace those values. Preserved rows remain exportable after archiving removes scan components; scope and triage columns are blank when the component is unavailable. Existing components supply current triage state. Legacy scans without an assessment retain live-data behavior; malformed assessments fail instead of silently using current data. Interactive lists and print rows also use preserved evidence for vulnerability/license filtering and risk ordering; current triage and release metadata remain separate.

New data-phase completions preserve a versioned assessment before publishing the completed status (V43 schema). It records library identity, license evidence/classification, provider lookup outcomes/timestamps, OSV fix assessment and individual findings, including fix conflicts, missing scores/KEV status and source attribution. The report and scan summary reader use this evidence even after shared Library/CVE data changes. Capturing again cannot replace the first assessment; ordinary stale scan saves cannot erase it. Invalid stored formats fail rather than falling back to an empty result.

Legacy scans with no assessment still use the existing live/archived read paths; their past findings are not reconstructed. Triage/deferral state and report generation time remain current. Other direct Library/CVE queries still require review; this is not yet full immutable evaluation history. Independent AI output is not part of the data-phase assessment. Concurrent provider persistence and incomplete enrichment failures retain their existing limits, and this capture does not prove all requested providers succeeded. The original offline generation remains referenced by the scan. Per-scan retention and separate reevaluation revisions remain pending.


The CI gate uses preserved findings, license and malware outcomes for the target and baseline. Stored unavailable lookups, missing malware evidence, or mismatched inventory cannot pass coverage using newer shared data. Triage, source findings and policy selection still use their current state; legacy scans without assessments still use live data.

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

### Business metrics & Grafana

In addition to the default JVM/HTTP meters, OsWL records these business metrics (all exposed via `/actuator/prometheus`; Prometheus names shown — dots become underscores):

| Metric | Type | Tags | Description |
|---|---|---|---|
| `oswl_scan_duration_seconds` | Timer | `outcome` (`completed`\|`failed`) | End-to-end scan pipeline duration |
| `oswl_quickimport_queue_depth` | Gauge | — | Quick Import jobs waiting for a worker slot |
| `oswl_quickimport_running` | Gauge | — | Quick Import jobs currently running |
| `oswl_components_ingested_total` | Counter | `ecosystem` | Components persisted by scan ingest |
| `oswl_ai_calls_total` | Counter | `provider` | Recorded AI calls |
| `oswl_ai_tokens_total` | Counter | `provider`, `direction` (`in`\|`out`) | AI prompt/completion tokens |
| `oswl_ai_cost_usd_total` | Counter | `provider` | Estimated AI spend (USD) |
| `oswl_gate_evaluations_total` | Counter | `outcome` (`pass`\|`fail`) | Security-gate evaluations |
| `oswl_external_api_calls_total` | Counter | `source` (`depsdev`, `osv`, `epss`, `kev`, `github-advisory`, `nvd`), `outcome` (`success`\|`failure`\|`ratelimited`) | Outbound calls to external data sources |

A ready-to-import Grafana dashboard covering these metrics ships at [`deploy/observability/grafana/oswl-dashboard.json`](../../deploy/observability/grafana/oswl-dashboard.json). Import it via **Dashboards → New → Import** — it prompts for a Prometheus datasource on import, so no JSON editing is needed.

---

## Offline snapshot bundles (v1.0.4)

Online and offline NVD results treat a supplied invalid or future `lastModified` value as unverified, including active records or records without a status. Findings and their original evidence remain available, but the lookup is not marked complete. A legacy active record with no revision field remains readable; a rejected record still requires a usable revision before it can be removed.

When supplied, `vulns[].nvdApplicability` must be a nonblank string containing the preserved NVD evidence. Non-string or blank values reject the entire import instead of silently becoming absent evidence. Missing or null fields remain compatible with legacy bundles; neither proves that environment applicability was evaluated. Scan exports preserve configuration evidence but do not renew its source date. Library queries load source collections separately so one CVE with several sources is not repeated in the findings list.

EPSS-containing scan exports and CLI builds selecting EPSS include FIRST/Empirical Security attribution and links to the [EPSS data guidance](https://www.first.org/epss/data), [FAQ](https://www.first.org/epss/faq), and [FIRST service terms](https://www.first.org/about/policies/terms). Reviewed on 2026-09-21: the FAQ requests attribution; the service terms describe limited cybersecurity-purpose copying/distribution with notices preserved. Those terms are not treated as blanket permission for normalized customer databases or externally hosted CSV redistribution. The manifest keeps `rightsStatus: unreviewed` and does not assign an SPDX license. `github-attributed` excludes EPSS records; inherited notices remain preserved. API access is for targeted lookups; use daily CSV files for bulk synchronization.

Application exports validate the completed ZIP against import limits before returning a download: decompressed entry and bundle sizes, metadata size, compression ratio, and JSONL line length. Highly repetitive entries are stored without compression to preserve the full evidence within the ratio limit; this can increase download size. Other limit violations fail the export without truncating advisory evidence. Validation uses temporary disk space and removes its staging files afterward; this check does not establish source completeness or redistribution rights.

**Settings → Admin → Offline Snapshot**

For air-gapped deployments (`OSWL_AIRGAPPED_ENABLED=true`), vulnerability and threat-intel data (OSV, deps.dev, FIRST.org EPSS, CISA KEV) is served from an imported snapshot rather than live APIs — no outbound HTTP is attempted, and components absent from the snapshot resolve as "no data".

NVD applicability evidence is retained as the optional JSON string `nvdApplicability` in vulnerability snapshot rows and preserved scan findings. It contains the received `configurations` tree and available `id`, `sourceIdentifier`, `lastModified`, and `vulnStatus` fields. The field remains null when both configurations and status are absent; missing, empty and explicit-null configurations are distinguishable inside retained metadata. Import does not interpret these conditions or promote candidates. The string is source evidence, not executable content or a verified applicability decision. Legacy bundles lack this evidence; older exporters may discard it. Existing source dates, size limits and data notices still apply.

When multiple CPE candidates or an offline result list supply different evidence for one NVD ID, the source adapter selects the uniquely newest observation only if every observation has valid lifecycle evidence and a usable `lastModified`. Different content at the newest timestamp, missing revisions and invalid evidence remain an unscored review candidate with alternatives in `nvdApplicability.conflictingRecords`, and lookup stays incomplete. JSON object-key order and matching-confidence differences alone do not create a conflict. A serialized conflict stays unverified on later reads. Selection does not depend on arrival order, repair incomplete source coverage or reconcile old shared rows.

During enrichment, an existing versioned NVD observation also blocks older or unorderable replacements and different preserved content at the same revision. Its stored evidence remains intact and the NVD lookup is marked unavailable. Equivalent JSON key order and equivalent timestamp representations are accepted. This guard does not reconstruct missing legacy revisions, retain a full observation journal, recompute mixed-source fields or implement withdrawal of stored findings.

New NVD lookups additionally write an observation journal before shared-row reconciliation. It contains package identity, UTC observation time, provider lookup completeness, current and withdrawn observations, and the supplied revision/configuration evidence. Failed or missing offline lookups remain incomplete; unsupported sources do not create invented observations. Failure to store this evidence fails enrichment rather than silently continuing. The journal survives shared CVE/library removal and scan archiving, but is deleted with the owning scan. Apply migration V47 and include the table in database backups. Legacy scans are not backfilled. Automatic reconciliation and journal transport in scan/offline exports remain pending; this does not grant redistribution rights or provide tamper-proof storage.

Continuous monitoring also records each OSV result before changing that library's CVEs, including empty, failed and missing results. A shared library is queried once and its observation is linked once to each participating latest scan, regardless of duplicate components. OSV journal payloads distinguish SCAN_ENRICHMENT from CONTINUOUS_MONITORING; older payloads without observationKind came from scan enrichment. This association does not rewrite the original scan assessment or claim that a later finding was known at scan time. The entire batch is journaled before any CVE writes or alerts; an observation-storage failure aborts that cycle without those side effects. Already stored observations remain available. Monitoring currently captures OSV only.

Monitoring applies the same existing MAL advisory rule as scan enrichment: an accepted new or matching existing MAL finding is CRITICAL and marks the shared library malicious. The library flag commits with the findings and alert rows, so a failed alert write rolls it back as well. Rejected stale revisions do not change the flag. Automatic withdrawal or clearing of a previously stored malicious flag is not implemented by this change.

OSV revision filtering, scan enrichment and monitoring share the same matching rule: the exact advisory ID takes precedence over a CVE alias regardless of row order. Multiple exact rows, or multiple CVE-alias rows without an exact match, remain ambiguous: the raw observation is retained, the result is incomplete, and no matching row is arbitrarily updated. Ambiguity first encountered at persistence aborts the write. Accepted monitoring findings match the exact OSV advisory ID first, then an existing CVE alias. Both paths apply the advisory revision guard, merge severity/source information, and fill missing CVSS pairs, summary and CWE metadata without creating a duplicate discovery alert. New discovery alerts and notification payloads are built after all aliases for the library have been processed, using the final stored severity and fix; a later alias cannot leave an earlier low severity or withheld fix in the new alert. Only the same advisory ID may replace the stored fix version. Monitoring also aggregates fix candidates from accepted aliases assigned to the same stored finding; distinct candidates are retained as a conflict and withheld from the finding and new alert. Rejected revisions do not contribute candidates. An existing conflict can clear only for a complete OSV-only history when a complete, explicitly unexpired response includes every recorded advisory with accepted revision/digest evidence and all of them provide the same nonempty fix without imported conflicts. Missing contributors, unknown fixes, incomplete coverage and mixed sources keep the conflict withheld; historical alerts are unchanged. OSV-only and OSV/GitHub consensus share the same check of every recorded OSV advisory before clearing a conflict; a former alias now assigned to a different CVE cannot resolve the old row by proposing the same version: missing or duplicate contributors, mismatched identity, stale or unverifiable revisions and expired or undated results block clearance. Missing, empty or incomplete OSV contribution history also blocks conflict clearance; a fresh partial agreement cannot identify all contributors to an old conflict. Complete recorded histories may still recover after current agreement. This does not reconstruct missing GitHub contribution history. A separately stored recoverable-history marker preserves known provenance across temporary incomplete coverage or expiry. A later complete refresh can restore current severity contributions and resolve a fix only when all recorded advisories pass the current-evidence checks. Missing advisory identity or revision/digest evidence invalidates that marker. Legacy incomplete histories remain unknown. This adds a field to the existing OSV evidence JSON without a SQL migration; older complete JSON remains readable, but older binaries with a strict JSON reader cannot read the new field, so a downgrade requires a matching pre-upgrade data backup. The deps.dev enrichment path also prefers an exact OSV advisory and copies a fix only from that ID, including when creating a finding; a CVE alias alone cannot confirm that advisory-specific fix. Existing CVSS pairs stay together, and missing severity is not guessed. Legacy, mixed-provider or incomplete contribution histories still use conservative severity aggregation; full alias contribution and withdrawal reconciliation remains incomplete.

Monitoring now commits shared CVE changes in the queried batch and all corresponding project alert rows in one transaction, after lookup and observation journaling finish. A finding or alert write failure rolls back both, allowing the next cycle to discover the findings again. Audit events and external notifications run only after that transaction commits; audit failure is logged without suppressing notification attempts. Observation rows already committed remain available. This does not recover alerts lost before this change, provide durable notification retries after a crash/send failure, or prevent all concurrent duplicate inserts. Large-batch transaction/lock budgets still require load validation.

Scan enrichment additionally journals the normalized GitHub lookup result and the deps.dev version response with only the advisory details requested for that component. GitHub lookup flags and partial findings remain intact. For deps.dev, missing requested advisory IDs are listed explicitly; a missing/unresolved version or a missing/stale advisory makes coverage incomplete. A resolved version with no advisory keys is a complete empty lookup. The recorded latest release remains version metadata, not a fixed-version claim. Live GitHub queries now retain supplied updatedAt and validated withdrawnAt values, with withdrawn observations separate from active findings. Both lists reach the journal even after a later page fails or active/withdrawn states conflict; cached findings remain available. Timestamps are observations, not a verified ordering or permission to delete shared findings. A supplied online updatedAt that is malformed, has the wrong type or lies in the future makes the lookup incomplete and withholds fixes while retaining independently matched findings. The same current-time check is used offline. Missing/null revision metadata remains unknown and retains legacy lookup behavior; it is not evidence of revision ordering. Missing or malformed lifecycle metadata, complete historical reconstruction, offline bundle propagation and source reconciliation remain separate work. Existing journal JSON gains optional fields; older strict readers may require a pre-upgrade backup for downgrade. The [official schema](https://docs.github.com/en/graphql/reference/security-advisories#securityadvisory) defines these fields; existing [GitHub Advisory Database attribution terms](https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features#advisory-database) still apply. The journal is not newly included in exports. The GitHub bulk collector helper now maps active and withdrawn observations, including partial lookups, to SnapshotVuln fields githubUpdatedAt/githubWithdrawnAt. It is not connected to the CLI writer: --sources github-advisory remains unavailable. Import support for these fields does not establish CLI output support. App import preserves them and offline lookup separates withdrawals from active findings. Stale or undated coverage and contradictory states remain incomplete. A future withdrawal date is retained as an unconfirmed finding with no fix, not treated as an effective withdrawal. Legacy bundles without these fields retain their prior behavior. Invalid timestamp types or syntax reject the import. The optional fields are omitted when absent; consumers must understand them before using new bundles. App exports reconstructed from shared CVE rows still do not preserve this lifecycle history.

A future GitHub withdrawal timestamp is not an effective withdrawal online either. If package identity and the installed version range independently match, the finding and supplied future timestamp remain available with incomplete lookup status and no confirmed fix. Versions outside the affected range are not added as findings. Malformed withdrawal timestamps still make the lookup incomplete without establishing a matched finding from that node.

When the bulk collector helper receives an incomplete GitHub lookup with malformed updatedAt text, it maps that optional timestamp to unknown and retains the matched findings, unresolved component key and withheld fixes. Valid sibling findings and later packages continue to be collected. Syntactically valid future timestamps remain in the helper result as unconfirmed evidence. The malformed timestamp text itself is not preserved in the typed bundle field; this is not full raw-response preservation.

Scan enrichment also records normalized OSV results before shared-row updates: the actual mapped query identity, findings and conflicting fixes, common-fix selection and reason, advisory revisions/digests, and supplied expiry. A resolved empty result is distinct from a failed or missing result. Incomplete offline snapshots keep the raw result but mark observation coverage incomplete; unsupported queries create no observations. This does not add full OSV advisory bodies or reconstruct historical withdrawals, and a recorded fix candidate is not a newly validated patch recommendation.

OSV results now also retain lifecycle metadata before withdrawal/range filtering: advisory ID, query and detail revisions, the supplied withdrawal value and its presence, interpreted withdrawal state, revision-match flag and original-content digest. This includes withdrawn, uncertain and unavailable details; an unavailable detail has UNKNOWN state and no invented digest. The same metadata is journaled for scan enrichment and monitoring. A withdrawal state alone is not an instruction to delete a finding: revision matching, complete lookup coverage and other sources still require reconciliation. These are metadata from newly observed responses, not full advisory bodies or a backfill. The field meanings follow the [OSV schema](https://ossf.github.io/osv-schema/#withdrawn-field); the schema does not grant rights to redistribute each provider's data.

During scan enrichment, database write failures (including optimistic-lock conflicts) now abort before the assessment is captured. The independent failure handler marks the scan failed instead of publishing a completed result from partially persisted findings. This failure handling alone does not add concurrent-write protection or make all shared-library updates atomic; earlier committed writes may remain.

V49 adds the non-null `library_cves.row_version` counter. CVE saves and cascaded library merges reject stale row versions rather than overwriting newer stored findings. Repository saves flush before acknowledging the version on detached objects reused by sequential source lookups; a batch conflict rolls back that batch. Rejected writes follow the scan failure path; there is no automatic retry with stale evidence. Apply V49 after V48 before deployment, retain the column in backups/on rollback, and never mix older writers that do not update the counter. This protects existing CVE rows, not concurrent creation of duplicate findings, all library metadata, or atomicity of an entire scan/monitoring run.

V48 adds nullable `library_cves.osv_revision_evidence`, recording accepted OSV revision/digest pairs per advisory ID. Scan enrichment and continuous monitoring reject older, unorderable or same-revision/different-digest observations before they update that advisory's stored finding. A known advisory also requires a usable digest; a newer valid revision can advance it. The unfiltered incoming response still enters the journal. Enrichment marks rejected OSV coverage incomplete and withholds the common fix; monitoring skips rejected updates. Corrupt stored revision JSON fails instead of disabling the guard. Legacy rows without evidence are not backfilled, and unrelated advisory IDs are tracked independently. V48 alone does not provide field-by-field source recomputation, automatic withdrawal, or concurrency locking; V49 adds row-version checks separately. Apply V48 before deploying, retain it on rollback and avoid mixed writers: older application versions do not maintain this evidence. Scan/offline exports do not yet preserve this column.

A resolved OSV response alone does not authorize severity recomputation when snapshot coverage is unknown. Enrichment passes its snapshot lookup status to the stored-evidence recorder; monitoring checks both the original component key and the mapped OSV query key. An incomplete key makes the severity contribution history incomplete, preserving the prior aggregate severity/CVSS rather than lowering it. An explicitly expired OSV result is also incomplete at persistence: findings and revision evidence remain, but individual/common fixes are withheld and severity cannot be lowered. Persistence does not renew the captured expiry. Results without an explicit expiry retain their existing compatibility behavior; this check alone does not prove freshness for undated evidence.

Accepted OSV revision evidence now also retains each advisory severity and its CVSS score/vector pair. Severity is recomputed only for OSV-only rows whose complete history was recorded from creation: current advisory severities determine the highest value and conflict flag, while the CVSS pair comes from one advisory at that severity (highest available score, advisory ID for ties). An explicit missing severity remains unscored; the finding is not withdrawn or deleted. Legacy revision JSON, pre-existing rows, mixed providers, incomplete lookups, unversioned contributions, or a newly observed CVE alias not yet included in history cannot authorize a downward correction. These cases retain conservative aggregation and need further reconciliation. For an already complete OSV-only history, a complete current response can add same-CVE alias contributions before recomputation: every alias must have an accepted revision/digest and must not belong to a different or ambiguous stored row. This makes severity/CVSS recomputation independent of response order without reconstructing legacy history or applying withdrawals. The extended JSON uses the existing V48 column; older application versions may not read it, so do not mix writers or roll back the application independently of a compatible database backup. Snapshot exports still do not transport this evidence.

When a component snapshot contains several originals for one OSV ID, lookup selects the newest revision only when every supplied original has valid revision/lifecycle evidence and no preserved fix conflict. Conflicting content at the newest instant, future revisions, mixed legacy records without originals and uncertain lifecycle metadata remain incomplete. Equivalent timestamp representations with identical content are accepted. The selected original determines current affectedness, withdrawal/reactivation and fixes; older originals and their lifecycle observations remain stored. This selection does not refresh source dates, clear unresolved coverage, change bulk-archive integrity rules or reconcile shared CVE rows.

HTTP-page duplicate detection now tracks IDs separately from retained records. A repeated ID still makes the lookup incomplete, while distinct records from the same page, later pages, or a partially malformed page reach the source conflict review. Identical records are stored once in that lookup. This does not reconcile old shared rows or select an authoritative revision.

NVD source results exclude `Rejected` records only when the retained ID matches and `lastModified` is parseable and not in the future. NVD timestamps without an offset are interpreted as UTC. Uncertain rejection evidence keeps the identifier and marks lookup incomplete; stale snapshot coverage stays incomplete. Status/revision metadata is now retained even without configurations. This filters current NVD contributions, preserves the source snapshot, and does not delete stored CVEs or other providers’ findings. Full source-specific retirement and reactivation reconciliation remains pending.

`SYSTEM_ADMIN` or `SETTINGS_SNAPSHOT_MANAGE` required. See [What's New in v1.0.4](Whats-New-v1.0.4.md).

Bundles are v2 format: each JSONL file is checksummed in `meta.json`, and per-source provenance (`bundleId`, `builtAt`, `asOf`, `origin`) is stored. A checksum mismatch is rejected before any data is written.

Scan exports also include `meta.json.dataNotices`: GitHub Advisory Database attribution and license links, normalization details, and the limits of these notices. This does not establish redistribution rights for the entire bundle or preserve all supplied record-level credits. Source-specific permissions and notice retention still need verification before redistribution; a GHSA alias alone does not identify the original source or license.

The OSV bulk collector checks each original advisory's modified timestamp before interpreting withdrawal or affected versions. A missing, unparseable or future revision fails collection instead of establishing coverage. Online and offline lookups share this revision-clock check. This does not validate all schema fields or prove the completeness of an upstream archive.

Migration V40 adds persistent snapshot generations, their source metadata and an active-generation pointer. Before the first import attempt publishes changes, the existing store is preserved as a baseline generation. Successful imports copy the resulting payloads and all source dates, provenance and supplied notices into a new generation and move the pointer in the same transaction. Copy or commit failures leave the prior active generation intact. Historical generations can be read by ID and are not rewritten by later Replace or Merge imports. This first implementation makes a full database-side copy per publication and does not prune history; plan database space for retained generations.  Deploy V40 before the new application; older application versions do not maintain this history, so do not treat their writes as tracked generations. Preserving existing data does not grant additional use or redistribution rights.

Migration V41 records an optional snapshot generation on each scan. Offline data enrichment selects the active generation once with a conditional database update; concurrent selection and later saves of stale entities cannot replace it. Retry uses the same generation. Enrichment bypasses shared library fetch caches and reads provider payloads, unresolved keys and source dates from that generation. KEV membership also uses the pinned store rather than the global catalog cache. Offline remediation target verification opens one active generation for the complete check. Scope is limited to each synchronous lookup sequence and is restored on exit, including failures; it is not inherited by worker threads. An offline-pinned scan cannot be overwritten by online enrichment; start a new scan for that mode. Online scans keep the existing lookup/cache behavior. These pins do not make shared Library/CVE rows or later reports immutable, and do not yet cover pre-enrichment ingest or independent AI tasks. Retention, rollback and immutable finding snapshots remain pending.

Snapshot publication runs in its own serializable transaction, including when called from another transaction. Concurrent MERGE imports cannot replace the date of retained old rows with a newer date based on an earlier read. A concurrency conflict rolls back the whole attempt and retries the already validated staged files up to three total attempts; persistent conflicts remain failures. The existing 300-second transaction timeout applies to each attempt. Success is logged after commit. Publication commits independently of a caller transaction, so rolling back the caller does not undo a completed import. Leave database pool capacity for the separate connection. This protects publication consistency; durable generations, activation pointers and whole-scan revision pinning remain separate work.

Each offline OSV, GitHub Advisory and NVD query batch reads findings, unresolved keys, source freshness and candidate expiry in one independent serializable transaction. A concurrent import cannot give old empty results the new bundle's coverage or date. GitHub Advisory and NVD carry that captured coverage through enrichment; remediation target verification uses the captured GitHub coverage. Incomplete GitHub evidence retains findings and conflict candidates while withholding fixes. A subsequent batch can read the committed update. When called inside an existing transaction, this short read needs a separate database connection; leave pool capacity for concurrent readers. This boundary covers one provider batch, not a whole scan, all sources together or a durable dataset revision pin.

Offline deps.dev version and advisory batches also read their payloads and source dates in independent serializable transactions. Version batches include unresolved component keys. Enrichment and target verification use the captured resolved/current status, so a concurrent publication cannot make old evidence current. Incomplete version evidence retains licenses and advisory IDs while withholding default, deprecation, latest-version and scorecard status. The two deps.dev batches are separate; this does not pin all sources to one scan revision.

Offline EPSS scores and their source freshness are read together in an independent serializable transaction. Stale or undated scores remain unavailable. KEV refresh reads catalog membership and its exclusive source-date expiry together and keeps that expiry in the cache. Later imports cannot renew an earlier catalog's evidence. Known membership remains positive; an absent ID remains unknown when the cached source evidence is expired or undated. The existing one-day cache refresh limit also applies. EPSS and KEV reads remain separate from the scan's other provider batches.

The lookup result carries its expiry through enrichment into the stored assessment. Later bundle updates cannot extend that result's lifetime. Online results capture their seven-day window when advisory evaluation starts; offline results retain the original source-date window. Missing date evidence remains missing, and expired candidates stay withheld. Existing stored assessments are not recalculated automatically; refresh them to replace any expiry previously derived from a later lookup. No database migration is required.

OSV's package name `*` matches all queried package names within the same ecosystem, as defined in the [OSV schema](https://ossf.github.io/osv-schema/#affectedpackage-field). This applies to supplied online originals, imported originals and bulk collection. Version ranges still decide impact; missing or unsupported ranges remain unknown. Common fixes must satisfy both wildcard and named-package constraints. Ecosystems and distribution releases remain separate, and partial names such as `exam*` are not patterns. Rebuild old bulk bundles and refresh scans/cached lookups to recover previously omitted wildcard findings. This does not guarantee that an upstream query returns every applicable advisory.

Bulk collection also requires a readable ZIP central directory and verifies each advisory entry's size and CRC. Truncated archives, inconsistent entries and duplicate advisory file names fail collection without replacing an existing output bundle. This applies to downloaded data, cache hits and `--offline-sources`. Validation uses a temporary copy of the compressed archive, removed on success or failure; allow temporary disk space for one archive. These structural checks detect incomplete or damaged ZIP files, not omissions already present in the publisher's dataset or source authenticity.

Within one OSV bulk archive, repeated advisory IDs must contain the same complete JSON content. Whitespace and object key order are ignored; arrays retain their order. Equivalent originals are processed once. Conflicting originals abort collection and leave an existing output bundle unchanged, regardless of archive order. The collector keeps a SHA-256 digest per ID rather than retaining every full original; this consistency check does not authenticate the source.

An OSV withdrawal takes effect only when its timestamp is valid and no later than the current clock. Future or malformed withdrawal metadata leaves coverage unresolved. Online and offline original-advisory lookups retain the advisory ID without proposing a fix; bulk collection marks the affected scope unresolved. A valid past withdrawal still removes the active finding.

Imports retain supplied dataNotices objects and the flat upstreamDataNotices array in source metadata. Merge unions distinct notices with those already stored; Replace adopts the supplied notices for each replaced source. Exports pass the retained objects through upstreamDataNotices alongside OsWL's own notices. This conservatively includes notices from imported sources, not a record-level attribution map or redistribution clearance. Repeated round trips deduplicate equal objects without nesting. Invalid or unreadable notices fail the operation; cumulative distinct notices above 512 KiB roll back import rather than discard credits. Previously discarded notices require reimport from the original bundle. Apply migration V39 before deployment.

An `osv.jsonl` vulnerability may optionally include an `osvAdvisory` JSON object containing its original OSV document. Its `id` must match `osvId`, `modified` must be a valid timestamp, and `affected` must be an array. The snapshot store retains the supplied document, including credits and references. Offline lookup derives identifiers, summary, CWE, severity and CVSS from the original document and rechecks membership and fixes against it; common fixes require original evidence for every contributing record and current, complete source coverage. Legacy records without it keep their existing lookup behavior but cannot establish a common fix. This field does not authorize acquiring or distributing upstream material. Automatic collectors still do not retain these originals. Scan exports can reuse stored originals under the content binding below; retain the source bundle and verify its rights and notices separately.

The application retains the OSV common-fix decision, reason, advisory revisions, currently affected IDs/aliases and an exclusive expiry in `libraries.osv_fix_assessment`, alongside the lookup attempt timestamp. Revision evidence includes supplied advisories that constrain an upgrade even when the installed version is unaffected. Screens and PR candidate selection use this common candidate only while coverage is complete, all stored findings are covered and no fix conflicts exist. Individual advisory fixes remain available separately. Online candidate display expires after seven days; offline expiry follows the original OSV source date and the configured calendar-day staleness window, so a later lookup does not renew old data. Old assessments without affected IDs or expiry remain unknown until refreshed. Long-lived/permanent caches may need clearing before rescanning to obtain the new evidence. Every PR still rechecks the target. Automatic original-document collection remains pending.

Accepted OSV originals now also have SHA-256 content digests stored in the common assessment JSON. Object key order and whitespace do not change the digest; array order and content do. Scan export attaches stored originals only when the exact component key, complete original ID set, modification times and content digests match the recorded OSV lookup, with all exported OSV findings covered and no fix conflict. Old assessments without digests, missing/extra/changed originals or failed lookups retain the ordinary projection without attaching unverifiable originals. Matching originals include currently unaffected constraints and preserve supplied credits/references. Such exports use formatVersion 3 and require an original-aware importer; exports without originals remain version 2, and versions 1/2 remain importable. Lines exceeding the 1 MiB import limit fail export rather than discard evidence. Re-export does not refresh source dates. Digests establish content consistency, not authenticity or redistribution rights. No additional DB migration is needed beyond the existing assessment column; refresh old assessments to capture digests.

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

For repeated keys in a Merge file, the last record replaces the complete stored payload, including any supplied original advisory. This is independent of internal batch boundaries; it does not combine fields from different revisions. Delete markers retain their position in the input sequence.

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

Embedded AI runs a separately installed llama.cpp runtime with **Qwen3.5-2B Q4_K_M** as the default download. **Gemma 4 E2B** is optional and installed manually. The runtime belongs in `embedded-ai/llama/`, and models in `embedded-ai/model/<family>/`. Boot-time prefetch downloads only; it does not start the server or activate LOCAL. The default download uses a pinned Hugging Face revision with SHA-256 and size verification; no fallback mirror is configured by default. Air-gapped mode disables downloads. Use Settings to stop, select and save a model, then start again. See [Embedded AI](Embedded-AI.md) for current requirements and configuration.

Only one provider is **active** at a time. The tab also exposes:

| Setting | Purpose |
|---|---|
| Prompt locale (`en` / `ko` / `ja`) | Chooses `prompts.properties` vs the Korean or Japanese overlay |
| CVE / license batch limits & severities | Caps enrichment AI calls per scan |
| Temperature / max tokens / daily call cap | LLM behaviour and cost guardrails (temperature is ignored for Anthropic — see above) |
| Default deployment profile | Context for CVE triage **and** license-risk assessment when a project has no profile — obligations differ sharply between an internal tool, a network service, and distributed software |
| Prompt overrides | Per-key template edits (see `GET /api/settings/ai/prompts`) |

**Test Connection** does not spend any tokens: it lists the provider's available models (`GET {base}/models` for OpenAI/Gemini/Ollama, `GET /v1/models` for Anthropic) rather than sending a completion, so checking credentials and reachability is free and does not count against the daily call cap. If the configured model ID is not among the models the account can access, the test still succeeds but shows a warning so a typo or an unpulled local model surfaces immediately instead of on the next scan.

**API:** `GET|PUT /api/settings/ai`, `POST /api/settings/ai/test-connection`, `POST /api/settings/ai/golden-test`.\
**Embedded AI:** `GET /api/settings/ai/embedded`, `POST .../embedded/start?model=`, `POST .../embedded/stop`, `PUT .../embedded/config` — see [API Reference — AI](API-Reference.md#ai).\
**Per project:** `PATCH /api/projects/{id}/deployment-profile`.\
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
- `TEAM` (default) — each SCIM group becomes a Team; members become TeamMember rows.
- `ROLE_TEMPLATE` — each SCIM group becomes a RoleTemplate; members are assigned that role template.

**User deactivation:** `DELETE /scim/v2/Users/{id}` sets `active=false` in OsWL. Users are never physically deleted via SCIM, preserving audit attribution.

**Audit actions:** SCIM operations are recorded as `SCIM.USER_CREATE`, `SCIM.USER_UPDATE`, `SCIM.USER_DEACTIVATE`, `SCIM.GROUP_CREATE`, `SCIM.GROUP_UPDATE`, `SCIM.GROUP_DELETE`, `SCIM.GROUP_MEMBER_ADD`, `SCIM.GROUP_MEMBER_REMOVE`, `SCIM.AUTH_FAILURE`, and `SCIM_KEY.CREATE`. SAML login events are recorded as `SAML.LOGIN_SUCCESS` and `SAML.LOGIN_FAILURE`.

CLI full and delta bundles also include `meta.json.dataNotices`: conditional GitHub Advisory Database attribution, CC-BY-4.0 and source links, a warranty disclaimer, and a description of selection/normalization and delta processing. Import and re-export retain this notice through the existing notice store. This closes the CLI metadata omission; it does not preserve all original credits, identify every record’s upstream rights, or authorize other sources. The [official database license](https://raw.githubusercontent.com/github/advisory-database/main/LICENSE.md) was checked on 2026-09-13. Automatic OSV bulk collection still omits original range evidence, so this notice does not enable common-fix selection from legacy summary records.

The VDB builder defaults to its connected collectors: `osv,epss,kev,depsdev`. Explicit `--sources github-advisory` or `--sources nvd` (including mixed selections) now fails before collection or output replacement. The unused `--github-advisory-token`, `--github-api-base` and `--nvd-api-key` options are also rejected. These collectors are not yet connected to this CLI; their online application clients and snapshot import support do not establish CLI collection support. Previously accepted requests could produce a successful bundle without the requested source. Existing bundles require checking their actual source content; this change does not backfill missing records.

VDB CLI `--sources` and `--ecosystems` lists reject empty values and empty comma-separated entries, including leading/trailing commas. Surrounding whitespace is trimmed and duplicate selections are collapsed in input order. Invalid lists stop before collection and leave an existing output bundle intact. This prevents comma-only source input from successfully replacing a bundle without collecting any source.

Delta baseline JSONL rows for supported sources are read strictly. Malformed JSON, trailing JSON values, duplicate fields, non-object rows, missing/non-text identities and duplicate record keys fail the build instead of being skipped or overwritten. Existing output remains intact on this failure; blank lines remain allowed. This checks row parsing and identity uniqueness, not the complete record schema, archive authenticity, manifest digests, or preservation of unselected sources.

CLI bundles now include source files and source dates only for collectors actually run. In delta mode, an unselected source is omitted, so it does not generate deletion markers or replace that source’s metadata on Merge import. A build without a wanted list also leaves prior unresolved-component evidence alone. Selecting a source still computes its normal additions, changes and deletions. This does not yet define deletion behavior when the wanted list or ecosystem filter changes within a selected source.

Delta baseline archives must have a readable ZIP central directory. Duplicate entry names and mismatched uncompressed size or CRC fail the build before output replacement. Truncated and non-ZIP files are rejected. This detects archive corruption and ambiguity; CRC does not authenticate the provider or replace manifest digest/signature validation. Archive resource limits and full manifest checks remain separate work.

`--since` requires a full baseline when `meta.json.mode` is present. A delta contains changes rather than the complete source state and cannot directly serve as the next baseline. Null, unknown and non-text modes, non-object metadata, duplicate JSON fields and trailing JSON values are rejected. Legacy bundles without a mode field remain readable; that compatibility does not prove their completeness. Use a complete full bundle as the comparison baseline.

Before delta comparison, a declared baseline manifest must exactly match the archive’s data-file inventory, SHA-256 values and nonblank line counts. Format versions 2 and 3 require the manifest; unsupported or malformed format versions fail. Legacy version 1 or versionless bundles may omit it, but a supplied manifest is still checked. These checks detect inconsistency, not provider authenticity or permission to redistribute data.

`oswl-vdb verify` now shares ZIP, manifest and supported JSONL-row integrity checks with delta baseline loading. It requires an explicit files manifest instead of succeeding without checksums. A structurally valid full or delta bundle can be verified; only a full state may be used as a declared delta comparison baseline. Success reports integrity checks, not source authenticity, complete vulnerability coverage or redistribution permission.

The CLI wanted JSONL loader rejects malformed, duplicate-field or trailing JSON and requires each nonblank row to be an object with nonblank string ecosystem, name and version fields. Invalid rows fail the build instead of disappearing from the requested coverage or being coerced from numbers. Blank lines remain allowed. Ecosystem-specific version validity is still evaluated by the existing collection logic; this input check does not claim a version is supported.

KEV bulk collection now requires a valid, non-future `dateReleased` timestamp and preserves its UTC date. Missing, malformed or date-only input fails collection instead of being replaced with the build date. This follows the [CISA schema](https://raw.githubusercontent.com/cisagov/kev-data/main/known_exploited_vulnerabilities_schema.json), checked on 2026-09-13. It validates release time, not the whole catalog schema or redistribution rights.

KEV bulk collection also checks the declared integer count against the vulnerability array, requires a nonblank catalog version, and rejects invalid or duplicate CVE identities. Invalid rows are not discarded to produce a partial successful bundle. These checks match the online client’s existing membership validation; the complete descriptive-field schema and release-to-release rollback/conflict policy are separate concerns.

Online KEV refresh and bulk collection now reject duplicate JSON fields at any depth and trailing JSON values. The online client parses response bytes explicitly so HTTP conversion cannot discard duplicate fields first. Ambiguous input does not establish a new catalog or a confirmed negative result; the existing cached-evidence policy remains in force. Tests apply identical synthetic input to mock HTTP and offline source files.

The GHSA bulk collector helper now retains successful empty lookups as explicit component keys and tracks unavailable or failed lookups separately in `unresolvedKeys`. Partial lookup exceptions retain their verified findings and withheld fixes. Unsupported or unconfigured clients are not queried. This helper is still not connected to the CLI writer, so this change does not enable `--sources github-advisory` or establish complete bundle provenance.

GHSA snapshot conversion also retains conflicting fix-version candidates. A conflict keeps the fix version unset, while a non-conflicting fix is preserved. JSON serialization and deserialization preserve that distinction; this does not enable the disconnected CLI source.

The NVD bulk helper preserves CPE match confidence and the supplied applicability evidence through snapshot conversion. A failed candidate does not discard other candidate results; partial responses retain their findings and mark the component in `unresolvedKeys`. Successful empty responses remain distinguishable from failures and unsupported ecosystems. This helper is not connected to the CLI writer, and this change does not enable `--sources nvd` or establish redistribution rights.

When a bundle declares `basedOnBundleId`, application import requires versioned delta metadata, a distinct new bundle ID, and MERGE mode. Every affected source must still identify the declared base; unresolved coverage may start empty only when another affected source matches. Mismatched or absent provenance, REPLACE overrides, and replay after the source has advanced are rejected in the publication transaction. Import a full bundle to restore a baseline before applying a different delta derived from it. IDs are consistency metadata, not authentication or proof of exact contents. Legacy full bundles remain readable, but a declared delta must include versioned metadata and distinct valid bundle/base IDs. The CLI also rejects delta generation from a baseline without a valid ID, preserving existing output; generate a new full bundle first.

An unbased MERGE into a nonempty source clears its bundle ID: retained rows mean the resulting dataset cannot claim to be the incoming bundle. Subsequent deltas declaring that ID are rejected until a full REPLACE restores a baseline. Initial import into an empty source and a validated delta retain their resulting IDs. This does not verify source authenticity or retroactively repair provenance written by older versions.

Component-scoped OSV/deps.dev delta generation requires the baseline and current build to have the same `wantedListId`. A different or missing identity is rejected before replacing the output; generate a new full bundle for a changed inventory or a legacy baseline without this metadata. The identity hashes the original wanted file bytes, so formatting changes also require a new full baseline. Global KEV/EPSS-only updates do not require a matching wanted file. This guard does not yet establish per-source ecosystem scope or per-record freshness for arbitrary partial merges.

Global KEV/EPSS-only builds do not emit package coverage or unresolved records, even if a wanted file is supplied. A delta updates the selected global source while retaining existing package uncertainty and its source metadata. Package coverage is computed only when OSV or deps.dev collection is selected.

CLI bundles now store an explicit empty OSV result for a concrete wanted package when its ecosystem archive was successfully processed and no unresolved evidence exists. This preserves a successful zero-finding lookup after offline import. Filtered ecosystems, invalid identities and unresolved ranges are not converted to empty successes; normal source-date expiry checks still apply.

Snapshot component identities cannot contain the internal `|` key separator in ecosystem, package name or version. Such identities are unresolved for offline lookup and rejected by CLI wanted-file loading and snapshot ingestion, preventing distinct coordinates from aliasing one stored key. Supported scoped npm names, Maven coordinates and distribution version punctuation retain their existing keys.

The OSV and GitHub Advisory identity validators also apply the shared snapshot-key rule before accepting a query identity. Ambiguous coordinates containing `|` must not become a successful zero-finding result from an empty online response or a complete snapshot. Normal coordinates retain their existing lookup behavior.

The source cache rejects future HTTP Last-Modified instants and future dates in local sidecar metadata as unknown freshness. Valid HTTP timestamps are converted to the UTC calendar date before caching. Unknown dates are not replaced with the download date; OSV collection continues to require a known source date.

EPSS bulk collection validates each normalized CVE identifier before accepting its score. A malformed identifier fails collection without replacing the output bundle; it is not silently skipped. Lowercase identifiers are normalized, and valid longer CVE sequence numbers remain supported. This validation concerns bulk collection, not a complete review of live EPSS request handling.

Live and offline EPSS requests normalize surrounding whitespace and case, validate the CVE format, and deduplicate before querying. Malformed identifiers are excluded from both HTTP and snapshot queries, not assigned a zero score. A returned score of zero remains a valid measurement.

Live EPSS responses are parsed with duplicate JSON field detection and rejection of trailing JSON values. Ambiguous responses yield no confirmed score for that batch instead of selecting the final field value. Other batches continue independently; missing scores are not converted to zero.

Offline EPSS bundle imports validate the normalized CVE identifier for both score and deletion records. Invalid identifiers reject the import and retain existing scores and source dates in both REPLACE and MERGE modes. Zero scores and valid long CVE sequence numbers remain supported.

OSV bulk collection rejects duplicate JSON fields, including nested fields, and trailing JSON values. Ambiguous advisory records abort collection instead of allowing the last field to replace package, range, revision, or withdrawal evidence. A failed CLI collection retains the existing output bundle.

OSV bulk collection now retains the full `osvAdvisory` object when its primary GHSA ID matches the same GitHub Advisory Database source URL on every affected entry. The accepted paths are `github-reviewed` and `unreviewed` JSON records under the official repository. Aliases alone, missing or mixed source declarations, different IDs, and other hosts do not enable original retention. Supplied credits, revision, ranges, and other fields remain intact. `dataNotices.githubAdvisoryDatabase.retainedOriginals` identifies the original-record location and its ID, source, and credits fields under the existing CC-BY-4.0 notice. URLs and credits stay in each original rather than being duplicated into a growing notice index. This recognizes declared attribution, not cache authenticity or permission for other sources. It restores offline common-fix evidence for these records; broader source support and distribution-profile enforcement remain incomplete. Application export still targets scanned libraries, not a full mirror of imported rows.

The [GitHub Advisory Database license](https://raw.githubusercontent.com/github/advisory-database/main/LICENSE.md) requires preservation of supplied attribution and notices, a license link, and modification indications. The bundle retains supplied original fields and records its selection/normalization, license, attribution, and disclaimer. [OSV aggregates multiple sources with different licenses](https://google.github.io/osv.dev/data/); this treatment does not apply to all OSV records.

The CLI writes a temporary bundle beside its destination and checks it with the application import staging limits before publishing it. Checks include decompressed entry/total size, metadata size, JSONL line length, and compression ratio. Validation failures retain the previous output and remove temporary files; original evidence is not truncated to fit. Publishing requires an atomic move on the destination filesystem and fails if unsupported. This transport check does not establish source authenticity, licensing approval, or complete semantic validity.

CLI `verify` and delta baseline loading apply the same import transport limits before reading bundle contents for manifest checks or comparison. Oversized lines and excessive compression are rejected even when checksums and line counts match. Verification is read-only and still does not certify source authenticity or complete semantic validity.

`--distribution-profile github-attributed --wanted <file>` restricts CLI output to OSV findings whose retained originals declare the supported GitHub Advisory Database source. It selects `osv` by default and rejects explicit selections of other sources before collection. Unattributed findings are excluded; every requested component remains unresolved for complete coverage, including components with retained findings or no findings. Source dates and original credits remain intact. The manifest records `distributionProfile`; deltas require the same profile as their full baseline. Rebuild a full bundle to change profiles.

The default `unreviewed` profile preserves existing source selection and is not redistribution approval. The selected profile implements an attribution-based output restriction, not cache authentication, a universal rights decision, or a runtime ban on previously imported data. Source-specific approval for other material and application-level distribution controls remain incomplete.

Application import, CLI verification and delta baseline loading share checks of the declared `github-attributed` profile against its contents. Only OSV and unresolved files are allowed; each finding must contain a matching attributed original, and full bundles must retain unresolved coverage for every finding component. Unresolved deletion markers are rejected, including in deltas. These checks validate profile consistency, not source authenticity or a legal approval certificate. Application import additionally checks the resulting database within the publication transaction: every OSV component in an attributed delta must retain an unresolved row, either inherited or supplied. Missing coverage rejects and rolls back the import, including explicit REPLACE requests.

The application stores each source’s declared profile and includes it in newly captured generation metadata. MERGE must match the stored profile, including for deltas with a matching base ID. Legacy null profiles are treated as unreviewed for this comparison; they are never inferred to be attributed or approved. A full REPLACE can change a source profile. This restriction does not yet filter exports or prevent every cross-source coverage interaction.

Imports also protect retained `github-attributed` OSV rows: after applying all source files, every such row must still have matching unresolved coverage. Replacing only the shared unresolved file cannot silently turn these partial lookups into complete results. Missing coverage rolls back the entire import; replacing OSV itself with a different full profile is still allowed.

Scan-derived bundle export preserves stored unresolved component keys even when a library’s aggregate lookup flag says resolved. CocoaPods uncertainty is emitted for both its package identity and its SwiftURL OSV query identity. A resolved library flag alone cannot erase incomplete snapshot evidence during export and reimport. This does not establish a redistribution clearance or preserve the bundle’s original distribution profile.

`GET /api/admin/snapshot/export?distributionProfile=github-attributed` exports only OSV originals whose GitHub attribution is recognized and whose revision/digest matches the stored scan assessment. Other sources and combined scan-summary fields are omitted. All exported component identities remain unresolved, including those with no retained findings. Original credits and supplied notices are retained. Unknown profiles are rejected; the default remains `unreviewed` and is not a redistribution approval. The restricted output is attribution-based, not an authentication certificate; missing, changed, or insufficiently linked originals are withheld. Current upstream freshness is not inferred from export time.
