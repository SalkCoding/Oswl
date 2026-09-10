# Database schema and migrations

OsWL stores all application data in PostgreSQL (`prod`) or H2 file-mode (`local`). JPA entities under `domain/entity/`, `auth/entity/` are the **source of truth** for the live schema.

---

## Profile behaviour

| Profile | `ddl-auto` | Meaning |
|---------|------------|---------|
| `local` | `update` | H2 schema is adjusted automatically when entities change |
| `prod` | `validate` | Startup fails if PostgreSQL does not match entities — **no auto-migration** |
| `test` | `create-drop` | In-memory schema per test run |

When upgrading a production database, apply SQL scripts from `src/main/resources/db/` **before** restarting the app on the new version.

### Flyway (v1.0.4, opt-in)

`OSWL_FLYWAY_ENABLED=true` enables the versioned migrations in `src/main/resources/db/migration/`; it defaults to `false`. The repository already provides `V1__baseline.sql` and subsequent migrations. On an empty PostgreSQL database, Flyway runs V1 and then the later versions before Hibernate validation. For an existing database without Flyway history, `baseline-on-migrate` records version 1 without executing V1, then runs V2 onward. Back up and compare the existing schema with these migrations before enabling it; manually applied changes can conflict with later migrations. Do not regenerate or edit a migration already applied to a shared database. If managing SQL manually, apply all required changes for the target version in order; the short list of legacy scripts below is not a complete fresh-install schema.

The migration directory also covers the current schema beyond v1.0.4: organizations and teams (V11), SAML/SCIM (V13), webhooks (V14), CVE sources and C/C++ metadata (V15–V16), policy inheritance and exceptions (V17, V28), reachability and evidence (V18, V29–V30), audit integrity (V19), UI preferences and onboarding (V20, V23–V24), secret/IaC findings (V21), scan archiving (V22), report branding (V25), cache counters and invalidation (V26–V27), and durable import jobs (V31). Use the actual migration files as the complete ordered inventory. Some migrations are not idempotent; do not blindly rerun them.

### Columns added in v1.0.4

| Table | Column | Type |
|---|---|---|
| `libraries` | `malicious` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `typosquat_risk` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `description` | `text` — upstream project blurb from deps.dev, shown on Component Detail |
| `libraries` | `homepage` | `varchar(500)` — project homepage URL, nullable |
| `libraries` | `source_repo_url` | `varchar(500)` — source repository URL, nullable |
| `scan_results` | `ai_locale` | `varchar(16)` — locale of the operator who started the scan, so AI Insight answers in their language |
| `scan_results` | `ai_status` | `varchar(20)` — AI enrichment progress (`NOT_APPLICABLE`/`PENDING`/`RUNNING`/`COMPLETED`/`FAILED`), tracked separately from scan `status` so a scan completes as soon as its data pipeline finishes |
| `ai_usage_events` | `branch` | `varchar(160)` — branch attribution for AI usage |
| `libraries` | `version_meta_fetched_at` | `timestamp` — last deps.dev version-metadata refresh; lets a cache-hit library skip GetVersion while within `oswl.cache.version-meta-ttl-seconds` (default 24h) |
| `libraries` | `license_expression_raw` | `text` — pre-join per-license list (JSON) from deps.dev, preserved so an offline snapshot export round-trips multi-license packages exactly |
| `libraries` | `ai_license_context_hash` | `varchar(64)` — SHA-256 of the fields driving the AI license-summary prompt; a re-scan with the same hash skips the AI call |
| `library_cves` | `ai_context_hash` | `varchar(64)` — same context-hash response caching for per-CVE AI summaries |

The two booleans carry SQL defaults specifically so `ddl-auto=update` can add them to a populated table; without the default, a `NOT NULL` column addition fails on existing rows. `description`/`homepage`/`source_repo_url` are populated lazily during enrichment (from the same deps.dev project call that already fetches the OpenSSF Scorecard) and stay `null` until the next scan for components that predate the column addition. All other new columns are nullable and idempotent (`ADD COLUMN IF NOT EXISTS`); a `null` hash/status simply behaves as a cache miss or `NOT_APPLICABLE` until the next scan populates it. The Flyway path is covered by `V3__component_metadata.sql` through `V7__snapshot_v2_and_license_raw.sql`.

### Tables added in v1.0.4

| Table | Purpose |
|---|---|
| `airgapped_snapshot_entries` | Air-gapped offline snapshot store — one row per `(source, entry_key)` with a JSON payload. Sources: `osv`, `depsdev-version`, `depsdev-advisory`, `epss`, `kev`, plus `unresolved` (wanted-list components the `oswl-vdb` builder could not resolve upstream) |
| `airgapped_snapshot_meta` | Per-source bookkeeping: `record_count`, `imported_at`, plus v2 bundle provenance — `bundle_id`, `built_at`, `source_as_of` (the upstream data's own as-of date the staleness UI keys off), `origin`, `format_version` (null = v1 bundle) |

`cve_alerts` and `jira_settings` also arrived with v1.0.4 (`V2__v104_features.sql`); see the migration script for their columns. The `airgapped_snapshot_*` tables are created by `airgapped_snapshot.sql` (below) and extended by `V7`.

---

## Manual migration scripts

| File | Purpose |
|------|---------|
| `project_members.sql` | Creates `project_members` for per-project ACL |
| `instance_setup_lock.sql` | Setup wizard lock table |
| `ai_enhancement.sql` | AI preference columns, `ai_daily_usage` table |
| `airgapped_snapshot.sql` | Air-gapped offline snapshot store (`airgapped_snapshot_entries`, `airgapped_snapshot_meta`) |
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

See [Production deployment checklist](Production-Deployment-Checklist.md) §9.

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

airgapped_snapshot_entries ── airgapped_snapshot_meta  (offline snapshot store)

users, role_templates, audit_logs, cache_settings, user_vcs_connections, …
```

- **Project card version / last scan** — derived from the latest `scan_results` row, not `projects.version`.
- **Enrichment cache** — `cache_settings` (Settings → Cache); controls OSV/deps.dev refetch TTL.
- **CWE** — stored on `library_cves` from OSV `database_specific.cwe_ids`.
- **Offline snapshot staleness** — driven by `airgapped_snapshot_meta.source_as_of` (upstream as-of date), not `imported_at`.

---

## Local reset

Stop the app, delete `oswl-db.mv.db` (and related H2 files), restart → empty DB and Setup wizard. No manual SQL needed in `local`.

---

## Related docs

- [Production deployment checklist](Production-Deployment-Checklist.md)
- [Administration](Administration.md) — Cache settings
- [Scan API security](Scan-Api-Security.md) — Audit-based submitter tracking
