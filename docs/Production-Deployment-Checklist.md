# Production deployment checklist

Use this one-page list before exposing OsWL on the internet. **Do not run `prod` with `local` defaults** (H2, Swagger, `/data/**`, or committed encryption keys).

## 1. Profile and build

| Check | Action |
|-------|--------|
| Profile | Set `SPRING_PROFILES_ACTIVE=prod` |
| JAR | Build with `./gradlew bootJar verifyProdJar` — `TestDataController` must **not** appear in the JAR |
| Local-only code | `oswl-app/src/local/java` is for `bootRun` / dev only, not packaged in `bootJar` |

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
| Actuator | Only **`/actuator/health`** exposed; all other endpoints disabled |
| Actuator auth | Requires **SYSTEM_ADMIN** session (not public) |

## 6. Security features enabled in prod

- Springdoc / Swagger UI: **off**
- H2 console and `/data/**`: **not in prod JAR** (local profile + `oswl-app/src/local/java` only)
- Security headers + HSTS (behind HTTPS): see `application-prod.yaml` `oswl.security.headers`
- Trusted-device cookie: `Secure` in prod

## 7. Optional secrets

| Variable | Purpose |
|----------|---------|
| `OSWL_TRUSTED_DEVICE_HMAC_KEY` | Dedicated HMAC key for `OSWL_TD` cookie (recommended; separate from `OSWL_ENCRYPTION_KEY`) |

## 8. Database schema (upgrades)

OsWL uses **Hibernate `ddl-auto=validate`** in `prod` — the app does not auto-alter PostgreSQL on startup.

| Profile | Schema management |
|---------|-------------------|
| `local` | `ddl-auto: update` — H2 schema follows JPA entities automatically |
| `prod` | `ddl-auto: validate` + **Flyway** (`oswl-app/src/main/resources/db/migration/`) |

**Flyway** runs automatically on prod startup. Legacy one-time scripts (if your DB predates Flyway) live in `oswl-app/src/main/resources/db/`:

| File | When to run |
|------|-------------|
| `api_key_machine.sql` | Only if Flyway V7 did not run (duplicate of `V7__api_key_machine.sql`) |
| `notification_settings.sql` | Legacy DB without notification settings table |
| `import_webhook.sql` | Legacy DB without project webhook columns |
| `project_members.sql` | First deploy of project ACL (if table missing) |
| `instance_setup_lock.sql` | First deploy after setup-lock feature |
| `ai_enhancement.sql` | Legacy installs predating AI preference columns / `ai_daily_usage` |
| `schema_cleanup.sql` | **Once** when upgrading to the release that removes unused tables/columns (`ai_feedback`, `external_api_settings`, `projects.version`, etc.) |

After running migrations, restart the app and confirm `validate` passes.

## 9. Post-deploy smoke test

1. Open UI via HTTPS reverse proxy only.
2. Complete setup / login and 2FA if enabled.
3. Create a project and VCS connection; restart app — token still decrypts (confirms stable `OSWL_ENCRYPTION_KEY`).
4. `POST /api/scan` with project API key (see [Scan API security](Scan-Api-Security.md)).
5. Open a project you are a member of — confirm another user’s project ID returns forbidden (project membership).
6. Review audit log for failed auth attempts.

## 10. Operations

- Back up PostgreSQL and store `OSWL_ENCRYPTION_KEY` in a secrets manager (loss = unreadable VCS tokens).
- Rotate API keys and SMTP credentials on compromise.
- Keep `SPRING_PROFILES_ACTIVE` out of images that should never run as `local`.

---

**Local development:** `SPRING_PROFILES_ACTIVE=local`, copy `.env.example` → `.env`, set `OSWL_ENCRYPTION_KEY`, run `./gradlew bootRun`. H2 file DB, H2 console, Swagger, and `GET /data/test` are available only in this profile.
