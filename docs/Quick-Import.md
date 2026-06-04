# Quick Import

Quick Import lets you pull a project directly from a VCS host — **GitHub**, **GitLab**, or **Bitbucket** — without writing CLI code.

---

## Supported Providers

| Provider | Authentication |
|---|---|
| GitHub | Personal Access Token (PAT) |
| GitLab | Personal Access Token (PAT) |
| Bitbucket | App Password |

---

## Step 1 — Add a VCS Connection

Go to **Settings → VCS** and click **Add Connection**.

| Field | Description |
|---|---|
| **Provider** | GitHub / GitLab / Bitbucket |
| **Display Name** | A friendly label (e.g. "GitHub – my-org") |
| **Access Token** | PAT or App Password with `repo` / `read_repository` scope |

OsWL validates the token against the provider API immediately. Tokens are stored **encrypted at rest** (`OSWL_ENCRYPTION_KEY` in production).

> Required permission: `SETTINGS_VCS_MANAGE` or System Admin.

---

## Step 2 — Import a Repository

Open **Projects → Quick Import** (`/projects/quick-import`).

You can either:

1. **Paste a repository URL** (and optional branch), then click **Import & Scan**, or  
2. **Browse** connected accounts — pick a repository and branch from the provider list.

### Progress and concurrency

Each import is an asynchronous **job** with its own progress card:

| Phase | Description |
|---|---|
| `QUEUED` | Waiting for a worker slot or starting soon |
| `CLONING` | Shallow-cloning the repository |
| `PARSING` | Detecting ecosystem and parsing dependency manifests |
| `SCANNING` | Creating the project and submitting the scan payload |
| `ENRICHING` | CVE/license enrichment and optional AI summaries |
| `DONE` | Import finished — project and API key available |
| `FAILED` | Error — see the job message |

- Up to **two** imports run at once (`oswl.quick-import.max-concurrent`, default `2`). Additional jobs are queued (FIFO); `queuePosition` shows wait order.
- You may start **multiple imports** without waiting for the previous one to finish.
- The UI subscribes to **`GET /api/quick-import/job/{jobId}/stream`** (SSE event `job-update`) and falls back to polling `GET /api/quick-import/job/{jobId}` if needed.
- During `ENRICHING`, the job exposes `percent` (0–100), `subPhase` (`CVE`, `LICENSE`, `POSTURE`, `TREND`, `DIFF`), `detailLines`, and `aiPreviews` when AI enrichment is enabled.

The temporary clone directory is deleted after ingestion.

---

## Re-importing a Branch

Import the same repository/branch again at any time to create a new scan result. Compare results in [Version Diff](Version-Diff.md) and [Risk Trend](Risk-Trend.md).

---

## GitHub Enterprise Server (GHES)

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab and Bitbucket self-hosted instances are supported via the API base URL in the VCS connection.

---

## REST API summary

See [API Reference — Quick Import](API-Reference.md#quick-import). Interactive schemas: Swagger UI (`local` profile).

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Token validation failed" | PAT scope missing (`repo` / `read_repository`) or token expired |
| "Repository not found" | Private repo without token access |
| Stuck at `CLONING` / `PARSING` | Network or disk space on the OsWL host |
| Stuck at `ENRICHING` | External API rate limits (NVD/OSV); retries continue in the pipeline |
| Job `404` on poll | Server restarted — in-memory jobs expire after ~30 minutes |
