# Project access control (technical reference)

> **Start here for a non-technical overview:** [Authorization layers](Authorization-Layers.md) explains role templates vs project membership vs system administrator.

## Overview

OsWL uses **two cooperating layers**:

1. **Global permissions** (`Permission` on **role templates**) — e.g. `SCAN_VIEW`, `LICENSE_EXPORT`.
2. **Project membership** (`project_members`) — whether the signed-in user may access a specific project.

A user typically needs the relevant permission **and** project membership. **System administrators** bypass membership.

## Data model

| Table | Purpose |
|-------|---------|
| `project_members` | Links `user_id` to `project_id` with role `ADMIN` or `MEMBER` |

- **ADMIN** (membership) — assigned to the project creator at creation time.
- **MEMBER** (membership) — default for other rows; feature gates still use global `Permission` values.

`projects.created_by_user_id` is used for bootstrap: on startup, projects with a creator and no members get the creator added as membership **ADMIN**.

## Enforcement

`ProjectAccessService` is the single entry point:

| Method | Use |
|--------|-----|
| `assertCanViewProject(projectId)` | UI and read/write APIs scoped by project; **403** if denied |
| `assertCanSubmitScan(projectId, userId)` | CLI scan ingest after API key + password |
| `accessibleProjectIds()` | Filters project lists and trash for non–system-admin users |

## Project-scoped surfaces (membership check)

These call `assertCanViewProject` (or equivalent service checks) before returning data:

| Area | Examples |
|------|----------|
| Analysis UI | Security Center, License (including exports), Component Detail, Version Diff, Risk Trend, Scan History |
| API | `GET/POST /api/projects/{projectId}/keys`, `GET /api/vcs/branches?projectId=`, scan status poll |
| Services | `ProjectService.getById`, `findAll`, trash operations filtered by accessible IDs |

## CLI scan authentication

`POST /api/scan` requires:

1. Valid project **API key** (interceptor).
2. Submitter **email + password** with `SCAN_SUBMIT`.
3. Submitter in **`project_members`** for that project.

Audit events include `SCAN.INGEST`, `SCAN.AUTH_FAILURE`, `SCAN.API_KEY_FAILURE`, `SCAN.AUTH_RATE_LIMITED`. Rate limits are configurable via `oswl.scan-api.*`.

## Fresh database vs upgrades

- **New installs:** Hibernate `ddl-auto` (or your schema tool) creates `project_members`; creators are added automatically.
- **Older databases:** ensure the `project_members` table exists, then restart so `ProjectMemberBootstrapRunner` can backfill creators where needed.

## Related docs

- [Authorization layers](Authorization-Layers.md)
- [Scan API security](Scan-Api-Security.md)
- [CLI Integration](CLI-Integration.md)
