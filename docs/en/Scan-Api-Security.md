# CLI scan API security

This page describes how OsWL protects **automated scan submission** (`POST /api/scan`) for security reviewers and operators. It does not replace [CLI Integration](CLI-Integration.md) for day-to-day setup.

---

## Why the browser and the CLI differ

| Client | Authentication | CSRF (browser token) |
|--------|----------------|----------------------|
| **Web UI** | Session cookie + login | Required on state-changing requests |
| **CLI / CI** | `Authorization: Bearer` project API key + submitter credentials | Not used — no session cookie |

Cross-site request forgery protections apply to **cookie-based browser sessions**. The CLI sends explicit secrets in headers and body; it does not rely on the logged-in browser session.

OsWL therefore **exempts only** these paths from CSRF checks:

- `POST /api/scan` — submit scan payload  
- `POST /api/scan/parse` — parse manifest archive (CLI step 1)  
- `POST /api/scan/gate` — evaluate the PR gate from CI (API-key authenticated, no browser session)  
- `GET /api/scan/ping` — verify API key  

All other routes keep normal CSRF protection for the UI.

---

## Authentication model (three checks)

| Step | What is verified |
|------|------------------|
| 1 | **Project API key** in `Authorization: Bearer …` |
| 2 | **Submitter** email and password in the JSON body |
| 3 | Submitter has **`SCAN_SUBMIT`** and project access through direct project membership or a team grant. |

See [Authorization layers](Authorization-Layers.md) for how role templates differ from project membership.

---

## Abuse resistance

- Failed API key or password attempts are **rate-limited** (configurable).
- Failures are written to the **audit log** (`SCAN.API_KEY_FAILURE`, `SCAN.AUTH_FAILURE`, `SCAN.AUTH_RATE_LIMITED`).
- Successful ingests are logged as `SCAN.INGEST` with the **submitter email** in the audit log (`actorEmail` / detail). OsWL does not store a `submitted_by_user_id` column on `scan_results`.
- CLI key issuance is logged as `CLI_KEY.CREATE` with the **current session user** as actor (not a column on `api_keys`).

---

## Scan status polling (browser)

`GET /api/scan/{scanId}/status` uses the **web session** and **project membership** — not the API-key-only path. It remains behind normal login and CSRF rules.

The response tracks scan status and **AI enrichment (`aiStatus`) separately**: a scan can reach `COMPLETED` while AI summaries are still `PENDING`/`RUNNING` in the background. Do not treat a completed scan status as proof that AI insights are ready — poll `aiStatus` for that.

---

## Operational recommendations

- Use **HTTPS** end-to-end (reverse proxy in production).
- Treat project API keys and submitter passwords like production secrets; rotate on leak.
- Review audit log filters for scan auth failures after incidents.
- Prefer dedicated CI service accounts with `SCAN_SUBMIT` and project membership.

Submitter passwords are sent in the JSON body today; use TLS and monitor audit events. Future releases may add token-based CLI auth — watch release notes.

---

## Related documentation

- [CLI Integration](CLI-Integration.md)
- [Authorization layers](Authorization-Layers.md)
- [Project access control](Project-Access-Control.md)
- [Production deployment checklist](Production-Deployment-Checklist.md)
