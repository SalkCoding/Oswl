# Scan History

The Scan History page lists all scans that have been submitted for a project, in reverse chronological order.

URL: `/projects/{id}/scan-history`

---

## What's Shown

| Column | Description |
|---|---|
| **Status** | PENDING / SCANNING / ANALYZING / COMPLETED / FAILED |
| **Version** | Project version string at scan time. FAILED rows also show an abbreviated error message under the version |
| **Scanned At** | Timestamp of scan submission (`yyyy-MM-dd HH:mm`) |
| **Source** | How the version was imported — **Git** (GitHub Integration) or **CLI** (CLI scan tool); `-` if no import record exists |
| **Components** | Total number of components detected |

The page header also shows the total number of scans.

---

## Scan Statuses

| Status | Description |
|---|---|
| `PENDING` | Scan received; queued for processing |
| `SCANNING` | Dependency manifests are being parsed |
| `ANALYZING` | CVE and license enrichment in progress (OSV / deps.dev) |
| `COMPLETED` | All enrichment done; Security Center is up to date |
| `FAILED` | An error occurred; check the error message on the scan row |

---

## Air-Gapped Mode: Definitions Date

When the server runs in offline (air-gapped) mode (`oswl.airgapped.enabled=true`), a banner appears at the top of the page:

> Offline mode — analyzed with vulnerability definitions as of {date}.

The date is the oldest definitions date among the imported offline snapshot sources, so auditors can see how fresh the underlying vulnerability data was at analysis time. The banner is hidden outside air-gapped mode or when no snapshot provenance is available.

---

## Deleting a Scan

Deletion is done from the version dropdown in the top bar: hover over a version entry, click the **Delete** (trash) icon that appears, then confirm. This requires the `SCAN_HISTORY_DELETE` permission (or the `SYSTEM_ADMIN` role) and permanently removes that scan record, including all its component data. The deletion is recorded in the audit log.

> ⚠️ This action is irreversible. Deleting a scan also removes it from the Risk Trend chart.

---

## Re-submitting a Scan

OsWL supports resubmitting a scan for the **same version** string. The server resets the existing scan record and re-processes the new payload. This is useful if a previous scan failed mid-enrichment or if dependencies changed without a version bump.

To re-scan: submit the same version via CLI (`POST /api/scan`) — OsWL detects the version match and resets the record automatically.
