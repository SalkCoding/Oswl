# Production deployment checklist

Use this one-page list before exposing OsWL on the internet. **Do not run `prod` with `local` defaults** (H2, Swagger, `/data/**`, or committed encryption keys).

## 1. Profile and build

| Check | Action |
|-------|--------|
| Profile | Set `SPRING_PROFILES_ACTIVE=prod` |
| JAR | Build with `./gradlew bootJar verifyProdJar` — `TestDataController` must **not** appear in the JAR |
| Local-only code | `src/local/java` is for `bootRun` / dev only, not packaged in `bootJar` |

## 2. Required environment variables

| Variable | Purpose |
|----------|---------|
| `DB_URL` | JDBC URL (e.g. `jdbc:postgresql://db:5432/oswl`) |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `OSWL_ENCRYPTION_KEY` | Instance encryption key (generate with `openssl rand -base64 32`) |

Copy `.env.prod.example` → `.env.prod` and fill every value. **No defaults** for DB or encryption in `application-prod.yaml`.

On startup, missing variables and other config issues are printed in **one `OSWL STARTUP WARNINGS` block** in the log (after the application is ready). In **`prod`**, if `OSWL_ENCRYPTION_KEY` is missing, the application **fails to start** — set a stable key before go-live. (The `local` profile may use a temporary key for development only.)

## 3. Network binding

| Check | Action |
|-------|--------|
| Default bind | `SERVER_ADDRESS=127.0.0.1` (see `application-prod.yaml`) |
| Public access | Put **nginx / Caddy / Traefik** (or cloud LB) in front; terminate TLS there |
| Direct `0.0.0.0` | Only if you accept exposing the JVM HTTP stack; document the risk and firewall |

`docker-compose.prod.yml` maps **`127.0.0.1:8080:8080`** so the container is not published on all interfaces by default.

Set `server.forward-headers-strategy=framework` (default in `application.yaml`) when the proxy sends `X-Forwarded-Proto` for HSTS and secure cookies.

## 4. Docker Compose (production)

```bash
cp .env.prod.example .env.prod
# Edit DB_*, OSWL_ENCRYPTION_KEY, SMTP_*
docker compose -f docker-compose.prod.yml up -d --build
```

Verify logs: no missing-env banner, PostgreSQL connected, no H2 or Swagger URLs.

## 5. Logging and observability

| Check | Action |
|-------|--------|
| Log levels | `prod` profile: `com.salkcoding.oswl` at **INFO** only; no DEBUG on AI/clients |
| AI excerpts | `oswl.ai.debug.log-prompt-excerpt` / `log-response-excerpt` default **false** in prod |
| Actuator | **`health`, `info`, `prometheus`** exposed (v1.0.4); everything else disabled (`enabled-by-default: false`) |
| Metrics scrape | Point Prometheus at `/actuator/prometheus` — the scraper must present admin credentials |
| Actuator auth | Requires **SYSTEM_ADMIN** session (not public) |

## 6. Security features enabled in prod

- Springdoc / Swagger UI: **off**
- H2 console and `/data/**`: **not in prod JAR** (local profile + `src/local/java` only)
- Security headers + HSTS (behind HTTPS): see `application-prod.yaml` `oswl.security.headers`
- Trusted-device cookie: `Secure` in prod

## 7. Optional secrets

| Variable | Purpose |
|----------|---------|
| `OSWL_TRUSTED_DEVICE_HMAC_KEY` | Dedicated HMAC key for `OSWL_TD` cookie (recommended; separate from `OSWL_ENCRYPTION_KEY`) |
| `OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` | **v1.0.4** — OIDC single sign-on. Also uncomment the `spring.security.oauth2.client` block in `application-prod.yaml`; the login page shows the SSO button only when a provider is registered. |

### v1.0.4 opt-in features

All default to **off** — enable deliberately.

| Variable | Default | Effect when enabled |
|---|---|---|
| `OSWL_FLYWAY_ENABLED` | `false` | Versioned migrations with `baseline-on-migrate`; generate a full baseline first |
| `OSWL_AIRGAPPED_ENABLED` | `false` | All vulnerability / threat-intel lookups served from an imported offline snapshot; no outbound HTTP |
| `OSWL_GATE_*` | see [What's New](Whats-New-v1.0.4.md) | Default thresholds for `POST /api/scan/gate` |

Continuous monitoring is the exception: `OSWL_MONITORING_ENABLED` defaults to **`true`** (nightly OSV re-query at 03:00, `OSWL_MONITORING_CRON`). It sends e-mail to project members, so confirm SMTP is configured before first launch — or set it to `false`.

## 8. Embedded AI model (optional, on-premise)

Only relevant if you plan to use **Embedded AI** (Settings → AI → Local) instead of, or in
addition to, a cloud provider.

| Check | Action |
|-------|--------|
| Server binary | Download `llama-server(.exe)` for your platform from the [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases) and place it in `embedded-ai/` (or `bin/` under it, or anywhere on `PATH`) — this is the only manual step |
| Model | Nothing to do — clicking **Start** on a fresh install downloads the Apache-2.0-licensed Qwen3-1.7B model automatically (~1.2 GB, verifies SHA256, shows progress in the UI) |
| Air-gapped hosts | The auto-download needs outbound internet access once. Without it, place a `.gguf` file (e.g. a manually fetched Gemma — see below) into `embedded-ai/` yourself before clicking Start |
| Gemma fallback | Never auto-downloaded — it's under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms), not a standard OSS license, so OsWL doesn't redistribute it on your behalf. Download it yourself if you want the low-spec fallback; see [Embedded AI](Embedded-AI.md) |
| Directory | Defaults to `./embedded-ai` relative to the working directory the JVM starts in — set `OSWL_EMBEDDED_AI_DIR` for a different path |

No Gradle task or separate script is involved — the download runs inside the application
itself the first time Start is clicked, so a plain `java -jar app.jar` deployment works.

## 9. Database schema (upgrades)

OsWL uses **Hibernate `ddl-auto=validate`** in `prod` — the app does not auto-alter PostgreSQL on startup.

| Profile | Schema management |
|---------|-------------------|
| `local` | `ddl-auto: update` — H2 schema follows JPA entities automatically |
| `prod` | `ddl-auto: validate` — run SQL scripts manually when upgrading |

Manual scripts live in `src/main/resources/db/`:

| File | When to run |
|------|-------------|
| `project_members.sql` | First deploy of project ACL (if table missing) |
| `instance_setup_lock.sql` | First deploy after setup-lock feature |
| `ai_enhancement.sql` | Legacy installs predating AI preference columns / `ai_daily_usage` |
| `schema_cleanup.sql` | **Once** when upgrading to the release that removes unused tables/columns (`ai_feedback`, `external_api_settings`, denormalized `projects.version`, etc.) |

After running migrations, restart the app and confirm `validate` passes.

### Flyway (v1.0.4, opt-in)

Set `OSWL_FLYWAY_ENABLED=true` to manage the schema with Flyway instead of hand-run scripts. `baseline-on-migrate` is enabled, so an existing populated database is baselined rather than rejected — but generate a full baseline migration that matches your current schema **before** turning it on. Left at the default `false`, nothing changes.

### v1.0.4 columns

This release adds `libraries.malicious` and `libraries.typosquat_risk`, both `NOT NULL DEFAULT false`. The defaults let the column be added to a populated table, so no manual script is required — but on `prod` (`ddl-auto: validate`) you still add them yourself. It also adds three nullable `libraries` columns (`description`, `homepage`, `source_repo_url`) for the upstream project metadata shown on Component Detail — `validate` checks that every mapped column exists regardless of nullability, so these need the same manual treatment:

```sql
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS malicious        boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS typosquat_risk   boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS description      text;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS homepage         varchar(500);
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS source_repo_url  varchar(500);
ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS ai_locale varchar(16);
```

(Flyway users: `V3__component_metadata.sql` covers the three new `libraries` columns; see [Database Schema](Database-Schema.md).)

## 10. Post-deploy smoke test

1. Open UI via HTTPS reverse proxy only.
2. Complete setup / login and 2FA if enabled.
3. Create a project and VCS connection; restart app — token still decrypts (confirms stable `OSWL_ENCRYPTION_KEY`).
4. `POST /api/scan` with project API key (see [Scan API security](Scan-Api-Security.md)).
5. Open a project you are a member of — confirm another user’s project ID returns forbidden (project membership).
6. Review audit log for failed auth attempts.

## 11. Operations

- Back up PostgreSQL and store `OSWL_ENCRYPTION_KEY` in a secrets manager (loss = unreadable VCS tokens).
- Rotate API keys and SMTP credentials on compromise.
- Keep `SPRING_PROFILES_ACTIVE` out of images that should never run as `local`.

---

**Local development:** `SPRING_PROFILES_ACTIVE=local`, copy `.env.example` → `.env`, set `OSWL_ENCRYPTION_KEY`, run `./gradlew bootRun`. H2 file DB, H2 console, Swagger, and `GET /data/test` are available only in this profile.
