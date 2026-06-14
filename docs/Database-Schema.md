# Database schema and migrations

OsWL stores all application data in PostgreSQL (`prod`) or H2 file-mode (`local`). JPA entities under `domain/entity/` are the **source of truth** for the live schema.

---

## Profile behaviour

| Profile | `ddl-auto` | Meaning |
|---------|------------|---------|
| `local` | `update` | H2 schema is adjusted automatically when entities change |
| `prod` | `validate` | Startup fails if PostgreSQL does not match entities — **no auto-migration** |
| `test` | `create-drop` | In-memory schema per test run |

When upgrading a production database, apply **Flyway migrations** from `oswl-app/src/main/resources/db/migration/` before restarting the app on the new version.

Legacy installs that predated Flyway may also need one-time scripts in `oswl-app/src/main/resources/db/` (see table below).

---

## Flyway (production)

| Setting | Value (`application-prod.yaml`) |
|---------|----------------------------------|
| Enabled | `true` (prod only) |
| Locations | `classpath:db/migration` |
| Baseline | `baseline-on-migrate: true`, `baseline-version: 6` |

| Migration | Purpose |
|-----------|---------|
| `V7__api_key_machine.sql` | `api_keys.type`, `bound_user_id` for CI machine tokens |
| `V8__project_members.sql` | `project_members` table |
| `V9__import_webhook.sql` | Per-project VCS webhook columns |
| `V10__notification_settings.sql` | Instance notification settings |
| `V11__instance_setup_lock.sql` | Setup wizard lock table |
| `V12__ai_enhancement.sql` | AI preference columns, `ai_daily_usage`, EPSS/KEV on `library_cves` |

`local` profile sets `flyway.enabled: false` and uses `ddl-auto: update` on H2.

---

## Legacy manual migration scripts

Use these **only** when upgrading an existing PostgreSQL database that was created before Flyway or before the corresponding entity landed. New greenfield prod installs should rely on Flyway + entity `validate`.

Scripts live in `oswl-app/src/main/resources/db/`:

| File | Purpose |
|------|---------|
| `api_key_machine.sql` | Same as Flyway V7 (if Flyway not yet applied) |
| `notification_settings.sql` | Instance notification settings table |
| `import_webhook.sql` | Per-project VCS webhook columns |
| `project_members.sql` | Creates `project_members` for per-project ACL |
| `instance_setup_lock.sql` | Setup wizard lock table |
| `ai_enhancement.sql` | AI preference columns, `ai_daily_usage` table |
| `schema_cleanup.sql` | **One-time** cleanup: drops unused tables/columns (see below) |

Run against PostgreSQL with any standard client (`psql`, DBeaver, CI migration job). Scripts use `IF EXISTS` / `IF NOT EXISTS` where possible.

### `schema_cleanup.sql` (upgrade note)

Run **once** when moving to a release that removed legacy schema:

| Removed | Reason |
|---------|--------|
| `ai_feedback` table | Never wired to JPA or UI |
| `external_api_settings` table | Replaced by `cache_settings` only |
| `api_keys.created_by_user_id` | Unused; issuance tracked in audit log (`CLI_KEY.CREATE`) |
| `scan_results.raw_payload`, `submitted_by_user_id` | Unused; submitter in audit log (`SCAN.INGEST`) |
| `project_versions.imported_at`, `last_updated_at` | Unused timestamps |
| `projects.updated_at`, `version`, `last_scanned_at` | Denormalized fields; UI reads latest `scan_results` instead |

See [Production deployment checklist](Production-Deployment-Checklist.md) §8.

---

## Core tables (overview)

```
projects
 ├── project_versions
 ├── project_members
 ├── scan_results
 │    └── scan_components → libraries (global)
 │         └── dependency_paths
 └── api_keys

libraries (shared)
 ├── library_cves  (CVE link + severity, CWE, AI fields)
 └── license data via enrichment

users, role_templates, audit_logs, cache_settings, vcs_connections, …
```

- **Project card version / last scan** — derived from the latest `scan_results` row, not `projects.version`.
- **Enrichment cache** — `cache_settings` (Settings → Cache); controls OSV/deps.dev refetch TTL.
- **CWE** — stored on `library_cves` from OSV `database_specific.cwe_ids`.

---

## Local reset

Stop the app, delete `oswl-db.mv.db` (and related H2 files), restart → empty DB and Setup wizard. No manual SQL needed in `local`.

---

## Related docs

- [Production deployment checklist](Production-Deployment-Checklist.md)
- [Administration](Administration.md) — Cache settings
- [Scan API security](Scan-Api-Security.md) — Audit-based submitter tracking
