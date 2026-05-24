# Quick Import

Quick Import lets you pull a project directly from a VCS (Version Control System) host — currently **GitHub**, **GitLab**, and **Bitbucket** — without writing any CLI code.

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

Fill in:

| Field | Description |
|---|---|
| **Provider** | GitHub / GitLab / Bitbucket |
| **Display Name** | A friendly label (e.g. "GitHub – my-org") |
| **Access Token** | PAT or App Password with `repo` / `read_repository` scope |

OsWL immediately validates the token against the provider's API and shows your authenticated username.  
The token is stored **encrypted** (AES-256-GCM) using `OSWL_ENCRYPTION_KEY`.

> Required permission: `SETTINGS_VCS_MANAGE` or System Admin.

---

## Step 2 — Import a Repository

Navigate to **Projects → Quick Import** (`/projects/quick-import`).

1. Select a VCS **connection** from the dropdown.
2. The account and its accessible **repositories** are fetched automatically.
3. Select a **repository**.
4. Select a **branch**.
5. Click **Import**.

OsWL clones the repository to a temporary directory, resolves the dependency tree, and submits the scan. A real-time progress indicator shows each phase:

| Phase | Description |
|---|---|
| `SCANNING` | Cloning repository and parsing dependency manifests |
| `ANALYZING` | Enriching CVE and license data from NVD / OSV / deps.dev |
| `COMPLETED` | Scan finished successfully |
| `FAILED` | An error occurred — check the error message on the scan record |

> Cloning uses the access token stored in the VCS connection. The temporary clone is deleted after ingestion.

---

## Re-importing a Branch

Import the same repository/branch again at any time to create a new scan result. OsWL records every scan separately so you can compare them in [Version Diff](Version-Diff.md) and [Risk Trend](Risk-Trend.md).

---

## GitHub Enterprise Server (GHES)

To use GitHub Enterprise Server, set the environment variable:

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab and Bitbucket self-hosted instances are supported by supplying the appropriate API base URL in the connection settings.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Token validation failed" | PAT scope is missing (`repo` / `read_repository`) or token expired |
| "Repository not found" | Repository is private and the token doesn't have access |
| Import stuck at `SCANNING` | Network issue reaching the VCS host, or the clone temp directory is full |
| Import stuck at `ANALYZING` | NVD / OSV / deps.dev rate limit hit; will retry automatically |
