# CLI Integration

OsWL provides an official CLI (`oswl`) and a REST API for submitting dependency scans from local machines or CI pipelines — without a web browser or VCS connection.

---

## Quick start (official CLI)

### 1. Install

**Mac / Linux**

```bash
curl -fsSL https://<your-server>/scripts/install.sh | bash
```

**Windows (PowerShell)**

```powershell
iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
```

**Prerequisites**

| Platform | Tools |
|---|---|
| Mac / Linux | `curl`, `jq`, `zip` |
| Windows | PowerShell 5.1+, `curl.exe` |

### 2. Save your API key (optional)

```bash
oswl auth --key oswl_<your_api_key> --server https://<your-server>
```

### 3. Scan your project

```bash
cd /your/project
oswl scan -k oswl_<your_api_key> -u you@company.com --server https://<your-server>
```

- `-u` (email) is **required**.
- `-p` (password) is optional — you are **prompted interactively** if omitted.
- `project_dir` defaults to the current directory when omitted.

**CI/CD example**

```bash
export OSWL_API_KEY=oswl_xxx
export OSWL_USERNAME=ci@company.com
export OSWL_PASSWORD=secret
export OSWL_SERVER_URL=https://sca.company.com
cd /your/project && oswl scan
```

### What the CLI does (user-visible)

```
[OsWL] Scanning dependencies... (version: 1.4.2)
[OsWL] Parsing manifests on server...
[OsWL] Found 128 component(s).
[OsWL] Sending to server: https://...
[OsWL] Scan submitted! scanId=87
       Analysis is running on the server. Check the Security Center for results.
```

The command you type does **not** change — parsing runs on the server using the **same engine as Quick Import**.

---

## Architecture

```
Your machine / CI
       │
       │  1. Zip manifest files (lock, pom, package.json, …)
       │     Rules: GET /scripts/manifest-rules.json
       │
       │  2. POST /api/scan/parse  (multipart archive)
       │     Authorization: Bearer oswl_<key>
       ▼
OsWL Server — DependencyManifestParserService (shared with Quick Import)
       │
       │  3. POST /api/scan  (JSON payload + submitter credentials)
       ▼
ScanIngestService → async CVE + license enrichment (OSV / deps.dev)
```

---

## Prerequisites

1. A **project** registered in OsWL.
2. A **project API key** (`oswl_...`) from **Settings → CLI** or the project API keys page.
3. A **user account** with `SCAN_SUBMIT` permission and membership in that project ([Authorization layers](Authorization-Layers.md)).

---

## API endpoints (CLI)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API key | Key validity check |
| `GET` | `/api/scan/manifest-rules` | API key | Manifest collection rules (JSON) |
| `GET` | `/scripts/manifest-rules.json` | None | Same rules (static file for CLI cache) |
| `POST` | `/api/scan/parse` | API key | Parse manifest zip → components |
| `POST` | `/api/scan` | API key + user password | Submit scan for enrichment |
| `GET` | `/api/scan/{scanId}/status` | Session | Poll scan status (UI) |

---

## API key management

### Project-scoped key

```
POST /api/projects/{projectId}/keys
```

UI: project → **Settings (⚙)** → **CLI** → **Generate Key**.

### Admin global keys

**Settings → Admin → CLI Keys** — see [API Reference](API-Reference.md).

---

## Submitting a scan (raw API)

Advanced integrations can call the API directly without the `oswl` script.

### Step 1 — Parse manifests (optional if you build `components` yourself)

```bash
curl -s -X POST https://oswl.example.com/api/scan/parse \
  -H "Authorization: Bearer oswl_<key>" \
  -F "archive=@manifests.zip"
```

Response:

```json
{
  "ecosystem": "MAVEN",
  "componentCount": 128,
  "components": [ … ]
}
```

### Step 2 — Submit scan

```
POST /api/scan
Authorization: Bearer oswl_<key>
Content-Type: application/json
```

```json
{
  "version": "1.4.2",
  "submitterEmail": "dev@company.com",
  "submitterPassword": "yourpassword",
  "components": [
    {
      "name": "org.springframework:spring-core",
      "version": "6.1.4",
      "ecosystem": "MAVEN",
      "dependencyInfo": "Direct",
      "dependencyPaths": []
    }
  ]
}
```

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `version` | string | ✅ | Project version at scan time |
| `submitterEmail` | string | ✅ | OsWL user email |
| `submitterPassword` | string | ✅ | Validated via BCrypt; never stored or logged |
| `components` | array | — | Discovered OSS components |
| `components[].name` | string | ✅ | Package name |
| `components[].version` | string | — | Package version |
| `components[].ecosystem` | string | ✅ | `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS` |
| `components[].dependencyInfo` | string | — | Human-readable path summary |
| `components[].dependencyPaths` | array | — | Optional path trees |

### Success response

```json
{
  "scanId": 87,
  "projectId": 42,
  "version": "1.4.2",
  "status": "PENDING",
  "message": "Scan received successfully"
}
```

### Poll status

```
GET /api/scan/{scanId}/status
```

```json
{ "scanId": 87, "status": "COMPLETED", "componentCount": 128 }
```

Status flow: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED` (or `FAILED`)

---

## GitHub Actions example

```yaml
- name: Install OsWL CLI
  run: curl -fsSL https://oswl.example.com/scripts/install.sh | bash

- name: Submit OsWL Scan
  env:
    OSWL_API_KEY: ${{ secrets.OSWL_API_KEY }}
    OSWL_USERNAME: ${{ secrets.OSWL_USERNAME }}
    OSWL_PASSWORD: ${{ secrets.OSWL_PASSWORD }}
    OSWL_SERVER_URL: https://oswl.example.com
  run: oswl scan
```

---

## Ecosystem values

| Ecosystem | Example name format |
|---|---|
| `MAVEN` | `org.springframework:spring-core` |
| `NPM` | `lodash`, `@angular/core` |
| `PYPI` | `requests`, `django` |
| `GO` | `github.com/gin-gonic/gin` |
| `CARGO` | `serde` |
| `NUGET` | `Newtonsoft.Json` |
| `RUBYGEMS` | `rails` |

---

## Related

- [Scan API Security](Scan-Api-Security.md)
- [Quick Import](Quick-Import.md) — same parser, remote Git URL
- [API Reference](API-Reference.md)
