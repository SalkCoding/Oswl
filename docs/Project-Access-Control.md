# Project access control (ACL)

## Overview

OsWL uses **two layers** of authorization:

1. **Global permissions** (`Permission` on roles) — e.g. `SCAN_VIEW`, `SETTINGS_CLI_KEY_MANAGE`.
2. **Project membership** (`project_members`) — whether the signed-in user may access a specific project.

A user must have the relevant global permission **and** be a member of the project (unless they are a system administrator).

## Data model

| Table | Purpose |
|-------|---------|
| `project_members` | Links `user_id` to `project_id` with role `ADMIN` or `MEMBER` |

- **ADMIN** — full access to project data and CLI key management (when global permissions allow).
- **MEMBER** — view security center, scan history, and submit scans when `SCAN_SUBMIT` is granted.

`projects.created_by_user_id` is used only for bootstrap: on startup, projects with a creator and no members get the creator added as `ADMIN`.

## Enforcement

`ProjectAccessService` is the single entry point:

- `assertCanViewProject(projectId)` — UI and read APIs; throws **403** if denied.
- `assertCanSubmitScan(projectId, userId)` — CLI scan ingest after API key + password auth.
- `accessibleProjectIds()` — filters project lists for non-admin users.

**System administrators** bypass membership checks.

## Protected APIs (minimum set)

| Endpoint | Check |
|----------|--------|
| `GET /api/scan/{scanId}/status` | Resolve scan → project → `assertCanViewProject` |
| `GET /projects/{id}/security-center` | `assertCanViewProject` |
| `GET /api/projects/{id}/keys` | `assertCanViewProject` |
| `GET /projects/{id}/scan-history` | `assertCanViewProject` |

`ProjectService.getById` and `findAll` also enforce membership at the service layer.

## CLI scan authentication

`POST /api/scan` requires:

1. Valid project **API key** (interceptor).
2. Valid submitter **email + password** with `SCAN_SUBMIT`.
3. Submitter must be a **project member**.

Audit actions: `SCAN.API_KEY_FAILURE`, `SCAN.AUTH_FAILURE`, `SCAN.AUTH_RATE_LIMITED`, `SCAN.INGEST`.

Rate limits (in-memory, configurable via `oswl.scan-api.*`) reduce brute force on API keys and submitter credentials.

## Operational notes

- Existing deployments: run DB migration `src/main/resources/db/project_members.sql`, then restart so `ProjectMemberBootstrapRunner` backfills creators.
- Projects without `created_by_user_id` and without members are visible only to system administrators until membership is assigned.
