# CLI scan API security (`POST /api/scan`)

## Team agreement (Sprint 5 / #18)

The browser CSRF mechanism **does not apply** to the CLI scan ingest API. This is intentional and documented here so security reviews have a single reference.

## Authentication model

| Layer | Mechanism |
|-------|-----------|
| Project scope | `Authorization: Bearer <project_api_key>` (validated by `ApiKeyAuthInterceptor`) |
| Submitter identity | JSON body: `submitterEmail` + `submitterPassword` |
| Authorization | Global permission `SCAN_SUBMIT` + project membership (`project_members`) |

CSRF protection targets **cookie-bound browser sessions**. The CLI does not send the session cookie for scan ingest; it sends a **secret API key** and user credentials. Cross-site form posts from a victim’s browser cannot satisfy this without knowing both secrets.

## CSRF exemption

In `SecurityConfig`, CSRF is disabled for `/api/scan/**` because:

1. Authentication is not session-cookie-based for this path.
2. Browsers do not automatically attach `Authorization: Bearer` on cross-origin requests unless CORS and credentials are explicitly configured; OsWL does not expose permissive CORS for scan ingest.
3. Requiring a synchronizer token would force storing the token in the CLI, duplicating the API key model without improving safety.

## Rate limiting and audit

Failed API key or submitter password attempts are throttled and written to the audit log (`SCAN.API_KEY_FAILURE`, `SCAN.AUTH_FAILURE`, `SCAN.AUTH_RATE_LIMITED`). See Sprint 3 implementation.

## UI poll endpoint

`GET /api/scan/{scanId}/status` uses the **session** and project ACL; it remains behind normal browser CSRF + cookie auth (not under `/api/scan/**` CSRF ignore for POST-only — note: current ignore is `/api/scan/**` which includes GET status; GET is safe from CSRF side effects but status is read-only).

If the CSRF ignore pattern is tightened in the future, limit it to `POST /api/scan` and `GET /api/scan/ping` only.

## Submitter password in JSON body (#39 — long-term)

Today the CLI sends `submitterEmail` + `submitterPassword` in the POST body (alongside the project API key). Passwords are `@JsonProperty(WRITE_ONLY)` and are **not** persisted in `rawJson`, but the secret still transits in the request body.

**Recommended direction (not yet implemented):**

| Approach | Description |
|----------|-------------|
| Scoped scan token | One-time or short-lived token exchanged via `POST /api/scan/token` using API key + password once; subsequent scans use `Authorization: Bearer <scan_token>` only. |
| OAuth / PAT | Map CLI to a personal access token with `SCAN_SUBMIT` scope (no password per ingest). |
| mTLS / IP allowlist | Complement tokens for high-assurance environments. |

Until a token exchange exists, treat the project API key + submitter password like production credentials: TLS only, rotate on leak, monitor `SCAN.AUTH_FAILURE` audit events.

## Related docs

- [CLI Integration](CLI-Integration.md)
- [Project access control](Project-Access-Control.md)

## Browser security headers (Sprint 5 / #20)

Configured in `SecurityConfig` via `oswl.security.headers.*`:

| Header | Default |
|--------|---------|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | draft (self + Alpine/htmx CDNs) |
| `Strict-Transport-Security` | when HTTPS or `X-Forwarded-Proto: https` |

Verify behind your reverse proxy: `curl -I -H "X-Forwarded-Proto: https" https://your-host/login`

## CSRF cookie (Sprint 5 / #17)

- `XSRF-TOKEN` cookie is **HttpOnly** (default `CookieCsrfTokenRepository`).
- JavaScript reads the token from `<meta name="csrf-token">` and sends `X-XSRF-TOKEN` (see `/js/oswl-csrf.js`).
- XSS cannot read the CSRF value from `document.cookie`.
