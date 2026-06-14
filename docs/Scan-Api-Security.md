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
- `POST /api/scan/parse` — parse manifest zip (CLI step 1)  
- `GET /api/scan/ping` — verify API key  
- `POST /api/import/webhook` — inbound VCS push webhooks (verified per-project secret, not session cookie)

`GET /api/scan/manifest-rules` is a safe (read-only) **GET** request and does not require a CSRF exemption (safe methods are not CSRF-checked).

All other routes keep normal CSRF protection for the UI.

---

## Authentication model (three checks)

| Step | What is verified |
|------|------------------|
| 1 | **Project API key** in `Authorization: Bearer …` |
| 2 | **Submitter** email and password in the JSON body — **or** a **machine token** bound to a user (password omitted) |
| 3 | Submitter (or bound user) has **`SCAN_SUBMIT`** and is in **`project_members`** for that project |

### CI machine tokens

Issue via `POST /api/projects/{id}/keys`:

```json
{ "machineToken": true, "boundUserEmail": "ci@company.com" }
```

The bound user must already exist, have `SCAN_SUBMIT`, and be a project member. `POST /api/scan` may omit `submitterPassword` (and `submitterEmail` if it matches the bound user). See [CLI Integration](CLI-Integration.md).

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

---

## Operational recommendations

- Use **HTTPS** end-to-end (reverse proxy in production).
- Treat project API keys and submitter passwords like production secrets; rotate on leak.
- Review audit log filters for scan auth failures after incidents.
- Prefer dedicated CI service accounts with `SCAN_SUBMIT` and project membership.
- Use **machine tokens** in CI to avoid storing submitter passwords in pipeline secrets.

Submitter passwords are sent in the JSON body for standard keys; use TLS and monitor audit events. Machine tokens bind scans to a fixed user without a password.

---

## Related documentation

- [CLI Integration](CLI-Integration.md)
- [Authorization layers](Authorization-Layers.md)
- [Project access control](Project-Access-Control.md)
- [Production deployment checklist](Production-Deployment-Checklist.md)
