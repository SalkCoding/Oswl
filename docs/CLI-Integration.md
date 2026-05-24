# CLI Integration

OsWL provides a REST API that lets any build tool, CI pipeline, or custom script submit dependency scans without requiring a web browser or VCS connection.

---

## Overview

```
Your build pipeline
       │
       │  POST /api/scan
       │  Authorization: Bearer oswl_<key>
       │  Body: { version, components[], submitterEmail, submitterPassword }
       ▼
OsWL Server
  ├── Authenticates API key → resolves project
  ├── Authenticates submitter credentials → checks SCAN_SUBMIT permission
  ├── Ingests dependency list
  └── Asynchronously enriches CVE + license data (NVD / OSV / deps.dev)
```

---

## Prerequisites

1. A **project** registered in OsWL (or create one via the dashboard first — any name).
2. A **project API key** (`oswl_...`) issued from **Settings → CLI** tab, or from the project's API keys page.
3. A **user account** with the `SCAN_SUBMIT` permission that will be used as the scan submitter.

---

## API Key Management

### Create a project-scoped key

```
POST /api/projects/{projectId}/api-keys
```

Via the UI: open the project → **Settings (⚙)** → **CLI** tab → **Generate Key**.

### List keys

```
GET /api/projects/{projectId}/api-keys
```

### Revoke a key

```
DELETE /api/projects/{projectId}/api-keys/{keyId}
```

### Admin global keys

System Admins can manage cross-project keys at **Settings → Admin → CLI Keys**:

```
GET    /api/admin/cli-keys
POST   /api/admin/cli-keys
PATCH  /api/admin/cli-keys/{keyId}/toggle    # enable / disable
```

---

## Authenticating with the API

All CLI endpoints require the header:

```
Authorization: Bearer oswl_<your_api_key>
```

### Test Your Key

```bash
curl -H "Authorization: Bearer oswl_<key>" \
     http://localhost:8080/api/scan/ping
```

Expected response:

```json
{ "status": "ok", "projectId": 42 }
```

---

## Submitting a Scan

```
POST /api/scan
Authorization: Bearer oswl_<key>
Content-Type: application/json
```

### Request Body

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
      "dependencyInfo": "Direct (1)",
      "dependencyPaths": [
        [
          { "name": "com.example:my-app", "version": "1.4.2" },
          { "name": "org.springframework:spring-core", "version": "6.1.4" }
        ]
      ]
    },
    {
      "name": "lodash",
      "version": "4.17.21",
      "ecosystem": "NPM",
      "dependencyInfo": "Transitive (2)",
      "dependencyPaths": []
    }
  ]
}
```

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `version` | string | ✅ | Project version at the time of the scan (e.g. `"1.4.2"`) |
| `submitterEmail` | string | ✅ | Email of the OsWL user submitting this scan |
| `submitterPassword` | string | ✅ | Password of the submitter — validated via BCrypt server-side; never stored or logged |
| `components` | array | — | List of discovered OSS components |
| `components[].name` | string | ✅ | Package name (`group:artifact` for Maven, package name for npm) |
| `components[].version` | string | — | Package version |
| `components[].ecosystem` | string | ✅ | One of: `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS` |
| `components[].dependencyInfo` | string | — | Human-readable path summary (e.g. `"Direct (1) + Transitive (3)"`) |
| `components[].dependencyPaths` | array of arrays | — | Full path trees from root to this component |

### Success Response

```json
{
  "scanId": 87,
  "projectId": 42,
  "version": "1.4.2",
  "status": "PENDING",
  "message": "Scan received successfully"
}
```

### Checking Scan Status

```
GET /api/scan/{scanId}/status
```

Returns:

```json
{
  "scanId": 87,
  "status": "COMPLETED",
  "componentCount": 138
}
```

Status values: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED` (or `FAILED`)

---

## GitHub Actions Example

```yaml
- name: Submit OsWL Scan
  run: |
    curl -s -X POST https://oswl.example.com/api/scan \
      -H "Authorization: Bearer ${{ secrets.OSWL_API_KEY }}" \
      -H "Content-Type: application/json" \
      -d @scan-payload.json
```

Generate `scan-payload.json` using your language's dependency resolver (Maven, npm ls, pip list, go list, etc.).

---

## Maven Example (Bash)

```bash
#!/usr/bin/env bash
# Collect Maven dependencies and submit to OsWL

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

# Build JSON payload
COMPONENTS=$(mvn dependency:list -DincludeScope=runtime -q | \
  grep ':.*:' | \
  awk '{print $1}' | \
  jq -R 'split(":") | {"name": "\(.[0]):\(.[1])", "version": .[3], "ecosystem": "MAVEN"}' | \
  jq -s '.')

PAYLOAD=$(jq -n \
  --arg ver "$VERSION" \
  --arg email "$OSWL_USER_EMAIL" \
  --arg pass "$OSWL_USER_PASSWORD" \
  --argjson comps "$COMPONENTS" \
  '{"version":$ver,"submitterEmail":$email,"submitterPassword":$pass,"components":$comps}')

curl -s -X POST "$OSWL_URL/api/scan" \
  -H "Authorization: Bearer $OSWL_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"
```

---

## Ecosystem Values

| Ecosystem | Example name format |
|---|---|
| `MAVEN` | `org.springframework:spring-core` |
| `NPM` | `lodash`, `@angular/core` |
| `PYPI` | `requests`, `django` |
| `GO` | `github.com/gin-gonic/gin` |
| `CARGO` | `serde` |
| `NUGET` | `Newtonsoft.Json` |
| `RUBYGEMS` | `rails` |
