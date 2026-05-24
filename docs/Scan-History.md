# Scan History

The Scan History page lists all scans that have been submitted for a project, in reverse chronological order.

URL: `/projects/{id}/scan-history`

---

## What's Shown

| Column | Description |
|---|---|
| **Scan ID** | Internal numeric identifier |
| **Version** | Project version string at scan time |
| **Status** | PENDING / SCANNING / ANALYZING / COMPLETED / FAILED |
| **Components** | Total number of components detected |
| **Submitted At** | Timestamp of scan submission |
| **Submitted By** | User email (if authenticated CLI scan or Quick Import) |

---

## Scan Statuses

| Status | Description |
|---|---|
| `PENDING` | Scan received; queued for processing |
| `SCANNING` | Dependency manifests are being parsed |
| `ANALYZING` | CVE and license enrichment in progress (NVD / OSV / deps.dev) |
| `COMPLETED` | All enrichment done; Security Center is up to date |
| `FAILED` | An error occurred; check the error message on the scan row |

---

## Deleting a Scan

Click the **Delete** icon on a scan row to permanently remove that scan record, including all its component data.

> ⚠️ This action is irreversible. Deleting a scan also removes it from the Risk Trend chart.

---

## Re-submitting a Scan

OsWL supports resubmitting a scan for the **same version** string. The server resets the existing scan record and re-processes the new payload. This is useful if a previous scan failed mid-enrichment or if dependencies changed without a version bump.

To re-scan: submit the same version via CLI (`POST /api/scan`) — OsWL detects the version match and resets the record automatically.
