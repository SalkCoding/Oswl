# User Guide

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

## Component Detail

Click any component (library) in the Security Center or License view to open the **Component Detail** panel.

It shows:

* Full name, version, and ecosystem
* CVE list with CVSS score, description, and fix version
* License name and compliance status
* AI-generated license risk summary (if AI is configured)
* Patchability status (Patchable / Non-Patchable / Unknown)
* Latest available version and deprecation notice (from deps.dev)
* Dependency path (how the component is pulled in)

---

## Glossary

For a full explanation of every term used in the UI — CVE, CVSS, SPDX, Patchability, etc. — see the [Glossary](Glossary.md).
