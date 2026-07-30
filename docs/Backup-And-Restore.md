# Backup and restore

The most common real-world incident isn't losing PostgreSQL — it's losing `OSWL_ENCRYPTION_KEY` while the database backup is fine. Every VCS access token, AI provider API key, Jira API token, and SMTP mail password stored in the database is encrypted with that key. Lose the key and the database restores perfectly but every one of those secrets is permanently unreadable — every VCS connection, AI provider, and Jira integration has to be reconfigured from scratch.

This page is the operator-facing counterpart to [Production deployment](Production-Deployment-Checklist) — read that first for how the app is deployed; this page is specifically about backing it up and proving a restore actually works.

---

## What to back up

| Item | Where | Why it matters |
|---|---|---|
| PostgreSQL database | `docker-compose.prod.yml` volume `db-data-prod`, or your managed PostgreSQL instance | All application data: projects, scans, findings, users, encrypted secrets. |
| `OSWL_ENCRYPTION_KEY` | Wherever you inject it (`.env.prod`, secrets manager) | Decrypts every VCS token / AI API key / Jira token / SMTP password in the database. **Without it, the database backup above is useless for anything requiring those secrets.** |
| Offline snapshot store | `OSWL_AIRGAPPED_IMPORT_DIR` (if air-gapped mode is used) | Re-importing after a restore is possible without this, but you lose your import history and have to re-fetch/re-verify bundles. |
| Embedded AI model directory | `OSWL_EMBEDDED_AI_DIR` (default `embedded-ai/`) | Re-downloadable (see [Embedded AI](Embedded-AI)) — back up only if you're air-gapped and can't re-fetch it. |
| Configuration files | `.env.prod`, `docker-compose.prod.yml`, any `application-prod.yaml` overrides | Without these, you know the *data* is fine but not how the instance was actually configured (SMTP host, HSTS settings, feature flags). |

Everything else (`OSWL_LOG_DIR` file logs, Quick Import clone temp dirs) is disposable — do not back it up.

---

## PostgreSQL backup

```bash
# Schema + data, custom format (supports parallel restore, smaller than plain SQL)
pg_dump -Fc -h <host> -U <user> -d <database> -f oswl-$(date +%Y%m%d).dump
```

**Recommended cadence:** nightly full dump, retained 30 days, plus PostgreSQL WAL archiving if you need point-in-time recovery between nightly dumps. Store dumps somewhere independent of the database host (object storage, a different availability zone) — a backup that lives next to the thing it backs up doesn't survive the incident that takes out the host.

Back up `OSWL_ENCRYPTION_KEY` **in a separate secrets manager**, not alongside the `pg_dump` output — the whole point of encrypting these secrets at rest is defeated if the key sits next to the encrypted data in the same backup blob.

---

## Restore procedure

1. **Provision a fresh PostgreSQL instance** (or wipe the target) and restore the dump:
   ```bash
   pg_restore -h <host> -U <user> -d <database> --clean --if-exists oswl-20260730.dump
   ```
2. **Inject the same `OSWL_ENCRYPTION_KEY`** the backed-up data was encrypted with — a different key (even a freshly generated valid-looking one) makes every stored secret undecryptable, indistinguishably from data corruption.
3. **Start the app** against the restored database (`SPRING_PROFILES_ACTIVE=prod`, `ddl-auto: validate` — the restored schema must already match the running version; apply any pending manual migration script from `src/main/resources/db/` *before* starting if you're restoring onto a newer app version than the backup was taken on).
4. **Run the verification script** below to confirm the restore is actually usable, not just "the process started."

```bash
OSWL_VERIFY_EMAIL=you@example.com \
OSWL_VERIFY_PASSWORD='...' \
OSWL_VERIFY_PROJECT_ID=1 \
./scripts/verify-restore.sh https://your-instance.example.com
```

The script is interactive (it pauses for your email OTP code, same as any real login) and checks:

| Check | What it proves |
|---|---|
| `GET /actuator/health` → 200 | The app started against the restored DB with the injected key. |
| Login + OTP | Auth and session infrastructure work against the restored `users` table. |
| `GET /api/settings/vcs` → 200 | **`OSWL_ENCRYPTION_KEY` is correct** — at least one stored VCS token decrypted without error. A wrong key surfaces here as a 500, not a subtle bug discovered weeks later. |
| `GET /projects/{id}/scan-history` → 200 | Scan history restored and queryable (needs `OSWL_VERIFY_PROJECT_ID` set to a project that exists in the restored data). |
| `GET /api/admin/audit-logs` → 200 | Audit log restored and queryable (needs a `SYSTEM_ADMIN` account). |

Run this as an actual scheduled **rehearsal** (e.g. quarterly, into a scratch environment) — a restore procedure nobody has run since it was written is not a tested procedure.

---

## Key rotation

There is currently **no re-encryption batch job**. Rotating `OSWL_ENCRYPTION_KEY` today makes every existing encrypted value (VCS tokens, AI API keys, Jira tokens, SMTP passwords) undecryptable — the practical rotation procedure is: rotate the key, then re-enter every secret through its settings UI (VCS connections, AI provider keys, Jira integration, SMTP credentials) so they get re-encrypted under the new key. A proper rotation tool (decrypt-with-old-key → re-encrypt-with-new-key, in place, across all affected tables) is tracked separately as a backlog item — until it exists, treat `OSWL_ENCRYPTION_KEY` as effectively permanent once set, and protect it accordingly (secrets manager, not a `.env` file in a repo).
