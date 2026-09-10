# User Guide

[What's new in 1.0.5.1](Whats-New-v1.0.5.1.md)

This page covers the day-to-day use of the OsWL web dashboard.

You only see projects you are a **member** of (unless you are a **system administrator**). What you can do inside a project depends on your **role template** (Admin / Developer / Viewer). See [Authorization layers](Authorization-Layers.md).

---

## Projects Dashboard (`/projects`)

The Projects dashboard is your landing page after login. It shows all active projects as cards.

### Project Card

Each card displays:

| Field | Description |
|---|---|
| **Name** | Project display name |
| **Version** | Version string from the latest scan |
| **VCS badge** | GitHub / GitLab / Bitbucket icon if the project was imported from VCS |
| **Branch** | Most recently imported branch |
| **Last Scanned** | Relative timestamp of the most recent completed scan |
| **Risk badges** | Count of CRITICAL / HIGH / MEDIUM / LOW CVEs |
| **License badge** | Worst license status (Restricted / Caution / Permitted / Unknown) |

### Project Actions

Click a project card to open the **Security Center**.  
The card's kebab menu (⋮) exposes:

* **View Details** — Security Center
* **License Analysis** — License risk page
* **Risk Trend** — Historical charts
* **Scan History** — List of all scans
* **Move to Trash** — Soft-delete the project

---

## Creating a Project

There are two ways to register a project:

1. **Quick Import** — connect a VCS account and pick a repository/branch. See [Quick Import](Quick-Import.md).
2. **CLI Push** — create a project with an API key, then push scan payloads from your build pipeline. See [CLI Integration](CLI-Integration.md).

### Quick Import progress

While a Quick Import is running, the progress card shows a continuous percentage and an estimated time remaining. During enrichment you may see per-component detail lines (for example, CVE counts). Once there are real deps.dev cache hits, a badge such as "1,204 components total — 1,180 served instantly from cache" appears; it stays hidden on the very first scan where nothing is cached yet. The job reaches **Done** as soon as the CVE and license data pipeline finishes — AI summaries may still be generating in the background.

---

## Trash

Deleted projects land in the **Trash** section (bottom of the projects list).

| Action | Description |
|---|---|
| **Restore** | Moves the project back to active |
| **Permanent Delete** | Irrecoverably removes all data (scans, CVEs, etc.) |
| **Restore Selected** | Bulk restore multiple projects |
| **Empty Trash** | Permanently deletes all trashed projects |

---

## Navigating a Project

Once inside a project, the sidebar provides access to:

| Section | URL pattern | Description |
|---|---|---|
| Security Center | `/projects/{id}/security-center` | CVE list and status management |
| License Analysis | `/projects/{id}/license` | Dependency license compliance |
| Risk Trend | `/projects/{id}/risk-trend` | Historical risk charts |
| Version Diff | `/projects/{id}/version-diff` | Compare two scans |
| Scan History | `/projects/{id}/scan-history` | All past scans |
| CLI / API Keys | Settings → CLI tab | Manage project API keys |

---

## AI Summaries

When AI is enabled, OsWL generates security posture, trend, and license-risk summaries **after** the scan data is ready. This means a scan completes and the Security Center becomes usable before the AI summaries finish. While AI is running, the Security Center shows a "Generating AI summary…" skeleton; the final insight appears without a page reload once it is ready. If AI is not configured, no skeleton is shown and the scan results are unaffected.

OsWL caches AI summaries using a context hash of the inputs, so unchanged components across rescans do not trigger new AI calls. You can still regenerate a single component's summary manually from the Component Detail panel.

---

## Offline (Air-Gapped) Mode

Administrators can run OsWL in air-gapped mode (`oswl.airgapped.enabled=true`). In this mode, vulnerability and threat-intelligence lookups (OSV, deps.dev, EPSS, CISA KEV) are served from an imported offline snapshot instead of live external APIs; no outbound HTTP is attempted.

Use **Administration → Offline Snapshot** to import a bundle, export the current store, or see per-source record counts and freshness. The freshness badge is based on the oldest upstream "as of" date across the imported sources, not the import time. Scans and exports analyzed from offline definitions carry an "Analyzed with vulnerability definitions as of YYYY-MM-DD" note. For setup details, see [Administration](Administration.md).

---

## Embedded AI Model

Embedded AI runs a separately installed llama.cpp runtime with **Qwen3.5-2B Q4_K_M** as the default download. **Gemma 4 E2B** is optional and installed manually. The runtime belongs in `embedded-ai/llama/`, and models in `embedded-ai/model/<family>/`. Boot-time prefetch downloads only; it does not start the server or activate LOCAL. The default download uses a pinned Hugging Face revision with SHA-256 and size verification; no fallback mirror is configured by default. Air-gapped mode disables downloads. Use Settings to stop, select and save a model, then start again. See [Embedded AI](Embedded-AI.md) for current requirements and configuration.

---

## Component Detail

Click any component (library) in the Security Center or License view to open the **Component Detail** panel.

It shows:

* Full name, version, and ecosystem
* A **project description**, homepage, and source repository link pulled from the upstream deps.dev project record — populated during enrichment at no extra API cost, since the same call already fetches the OpenSSF Scorecard
* CVE list with CVSS score, description, and fix version, visually grouped under a **Security Issues** heading separate from the license/version badges above it
* License name and compliance status
* AI-generated license risk summary (if AI is configured)
* Patchability status (Patchable / Non-Patchable / Unknown)
* Latest available version and deprecation notice (from deps.dev)
* Dependency path (how the component is pulled in)

If no upstream description is published for a package, the panel says so explicitly and points to the source-repo link instead of showing nothing.

---

## Glossary

For a full explanation of every term used in the UI — CVE, CVSS, SPDX, Patchability, etc. — see the [Glossary](Glossary.md).
