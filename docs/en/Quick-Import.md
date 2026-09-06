# Quick Import

Quick Import lets you pull a project directly from a VCS host — **GitHub**, **GitLab**, or **Bitbucket** — without writing CLI code.

---

## Supported Providers

| Provider | Authentication |
|---|---|
| GitHub | Personal Access Token (PAT) |
| GitLab | Personal Access Token (PAT) |
| Bitbucket | App Password |

---

## Step 1 — Add a VCS Connection

Go to **Settings → VCS** and click **Add Connection**.

| Field | Description |
|---|---|
| **Provider** | GitHub / GitLab / Bitbucket |
| **Display Name** | A friendly label (e.g. "GitHub – my-org") |
| **Access Token** | PAT or App Password with `repo` / `read_repository` scope |

OsWL validates the token against the provider API immediately. Tokens are stored **encrypted at rest** (`OSWL_ENCRYPTION_KEY` in production).

> Required permission: `SETTINGS_VCS_MANAGE` or System Admin.

---

## Step 2 — Import a Repository

Open **Projects → Quick Import** (`/projects/quick-import`).

You can either:

1. **Paste a repository URL** (and optional branch), then click **Import & Scan**, or  
2. **Browse** connected accounts — pick a repository and branch from the provider list. GitHub listings follow the API's `Link`-header pagination (100 repos per page, up to 1,000 repos per account/org); larger accounts are truncated rather than failing to load.

### Progress and concurrency

Each import is an asynchronous **job** with its own progress card:

| Phase | Description |
|---|---|
| `QUEUED` | Waiting for a worker slot or starting soon |
| `CLONING` | Cloning the repository — by default a blobless sparse checkout of manifest files only, with a full shallow-clone fallback |
| `PARSING` | Detecting ecosystem and parsing dependency manifests |
| `SCANNING` | Creating the project and submitting the scan payload |
| `ENRICHING` | CVE/license data pipeline (deps.dev, OSV, threat intel) |
| `DONE` | Data pipeline complete — CVE/license results are ready; AI summaries may still be generating in the background |
| `FAILED` | Error or canceled — see the job message |

The job card exposes these live fields in addition to the phase:

| Field | Description |
|---|---|
| `percent` | Continuous 0–100 progress. Bands are `CLONING` 5–20, `PARSING` 20–40 (manifests processed N/M), `SCANNING` 40–55, `ENRICHING` data fetch 55–80, and AI blocks 80–100. |
| `subPhase` | Current enrichment block: `CVE`, `LICENSE`, or `INSIGHTS` (posture, security/license trend, and version diff were folded into one combined call in v1.0.4). |
| `detailLines` | Rolling log lines such as batch progress and per-library finding summaries. |
| `aiPreviews` | Rolling tail of free-form AI output. As of v1.0.4 this field is not populated during normal scans because the combined-insights call returns JSON rather than streaming text. |
| `aiStatus` | `NOT_APPLICABLE`, `PENDING`, `RUNNING`, `COMPLETED`, or `FAILED`. The job can reach `DONE` while `aiStatus` is still `PENDING` or `RUNNING`. |
| `cacheTotal` / `cacheHit` / `cacheToFetch` | deps.dev cache decision; shown as a cache-hit badge when at least one component is served from cache. |

Concurrency and delivery:

- Up to **three** imports run at once (`oswl.quick-import.max-concurrent`, default `3`). Additional jobs are queued (FIFO); `queuePosition` shows wait order. Each user may queue up to **three** jobs (`oswl.quick-import.max-queued-per-user`, default `3`).
- You may start **multiple imports** without waiting for the previous one to finish.
- Starting an import for a repository that already has a queued or running job is rejected with **409 Conflict** — wait for it or cancel it first.
- Each job card has a **Cancel** button (`POST /api/quick-import/job/{jobId}/cancel`): queued jobs stop immediately, running jobs stop at the next phase boundary (a long clone finishes first). Canceled jobs appear as `FAILED` with a "canceled" message. Once a job reaches `DONE`, any still-running AI enrichment continues independently and is not stopped by canceling the Quick Import job.
- The UI subscribes to **`GET /api/quick-import/job/{jobId}/stream`** (SSE event `job-update`) and falls back to polling `GET /api/quick-import/job/{jobId}` if needed. High-frequency progress updates are throttled to one SSE frame per 500 ms unless the percent advances.
- When the scan finishes, a single `[Timing]` INFO log line summarizes elapsed seconds per phase (`clone`, `parse`, `ingest`, `depsdev`, `osv`, `threatintel`, `ai.*`, `cleanup`).

The temporary clone directory is queued for asynchronous deletion after ingestion.

### Shared parser with CLI

Dependency detection and manifest parsing use **`DependencyManifestParserService`** — the same engine as the official CLI (`oswl scan`). The CLI uploads a zip of manifest files collected per `GET /api/scan/manifest-rules` (static copy: `/scripts/manifest-rules.json`); Quick Import clones the repo (blobless sparse checkout by default) and walks the tree exactly once with the same rules. See [CLI Integration](CLI-Integration.md).

### Performance notes

Several v1.0.4 pipeline changes affect Quick Import speed and resource use:

- **Clone**: the default blobless sparse checkout (`--filter=blob:none --sparse`) fetches only the manifest file patterns required by the parser. Servers that do not support partial clone, or build-tool execution mode (`oswl.quick-import.allow-build-exec=true`), fall back to a full shallow clone.
- **Parse**: the cloned tree is walked exactly once to build a manifest index, replacing the previous multiple per-ecosystem walks.
- **Cleanup**: temporary clone directories are deleted asynchronously so deletion no longer blocks phase transitions.
- **Enrichment HTTP clients**: deps.dev and OSV clients use explicit connect/read timeouts. deps.dev requests are deduplicated by distinct `(ecosystem, name, version)`, version-metadata cache hits are skipped with their own TTL, and concurrency is configurable (`oswl.client.deps-dev.max-concurrent`, default `24`).
- **Ingest**: libraries are resolved with a bulk query and saved in chunks; CVE threat-intel updates are batched.
- **AI**: identical CVE/license contexts (hashed from severity, CVSS, fix version, EPSS bucket, KEV status, deployment profile, etc.) reuse the previous AI summary. Posture, trend, and diff insights are generated in a single combined call.
- **Air-gapped mode**: when `oswl.airgapped.enabled=true`, scan result pages and SBOM/VEX/SARIF exports include a vulnerability-definition date banner.

---

## Import an SBOM instead (v1.0.4)

If you can't clone the source — a vendor deliverable, a container base image, or an SBOM produced by another tool — upload its CycloneDX file instead:

**Quick Import → Import SBOM**, or `POST /api/sbom/import` (multipart).

Components are read from the CycloneDX `components` array using their `purl`, then enriched exactly like a cloned scan: CVEs, licences, KEV / EPSS, and supply-chain badges all apply.

---

## Re-importing a Branch

Import the same repository/branch again at any time to create a new scan result. Compare results in [Version Diff](Version-Diff.md) and [Risk Trend](Risk-Trend.md).

---

## GitHub Enterprise Server (GHES)

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab and Bitbucket self-hosted instances are supported via the API base URL in the VCS connection.

---

## REST API summary

See [API Reference — Quick Import](API-Reference.md#quick-import). Interactive schemas: Swagger UI (`local` profile).

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Token validation failed" | PAT scope missing (`repo` / `read_repository`) or token expired |
| "Repository not found" | Private repo without token access |
| Stuck at `CLONING` / `PARSING` | Network or disk space on the OsWL host |
| Stuck at `ENRICHING` | External API rate limits (OSV / deps.dev); retries continue in the pipeline |
| `DONE` still says "AI summary generating…" | Expected — the CVE/license data is ready; AI summaries finish in the background. The project page polls `aiStatus` and updates without a reload. |
| Cache badge shows "N served from cache" | Normal on re-scans; deps.dev/OSV cache hits skip refetch |
| "Analyzed with vulnerability definitions as of …" banner | Appears in air-gapped mode (`oswl.airgapped.enabled=true`) to show the snapshot's oldest upstream definition date |
| Job `404` on poll | Server restarted — in-memory jobs expire after ~30 minutes |
