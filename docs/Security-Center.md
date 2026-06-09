# Security Center

The Security Center is the primary vulnerability management interface in OsWL. It provides a filterable, sortable list of every CVE affecting the components detected in the latest scan for a project.

URL: `/projects/{id}/security-center`

**Access:** Requires `SECURITY_CENTER_VIEW` (or System Admin) **and** [project membership](Authorization-Layers.md). Export and print require `SECURITY_CENTER_EXPORT`. Status changes require `SECURITY_CENTER_UPDATE_STATUS`.

---

## Understanding the CVE List

Each row in the Security Center represents one **CVE** (Common Vulnerabilities and Exposures) entry linked to a scanned component.

| Column | Description |
|---|---|
| **Component** | Library name and version |
| **Ecosystem** | Package ecosystem (MAVEN, NPM, PYPI, …) |
| **CVE ID** | CVE or GHSA identifier from deps.dev/OSV (e.g. `CVE-2021-44228`) |
| **CVSS Score** | Numeric severity score (0.0–10.0) |
| **Severity** | CRITICAL / HIGH / MEDIUM / LOW |
| **Fix Version** | Suggested remediation version (if known) |
| **Status** | Current triage status |
| **Patchability** | Whether a fix is available |

---

## Severity Levels

OsWL maps CVSS base scores to severity buckets using the standard CVSS 3.x ranges:

| Severity | CVSS Score Range | Meaning |
|---|---|---|
| **CRITICAL** | 9.0 – 10.0 | Remotely exploitable, high impact — fix immediately |
| **HIGH** | 7.0 – 8.9 | Serious risk — prioritize this sprint |
| **MEDIUM** | 4.0 – 6.9 | Moderate risk — fix in the near term |
| **LOW** | 0.1 – 3.9 | Minimal risk — fix when convenient |
| **NONE** | 0.0 | Informational / no active score |

---

## CVE Status Lifecycle

Every CVE on a project can be assigned one of the following statuses:

| Status | Meaning |
|---|---|
| **OPEN** | Newly detected — requires review |
| **IN_PROGRESS** | Being actively remediated |
| **SUPPRESSED** | Acknowledged risk; not an immediate priority in this context |
| **FALSE_POSITIVE** | Confirmed not applicable (e.g. the vulnerable code path is not reachable) |
| **RESOLVED** | Fixed — upgrade applied or component removed |

> Required permission to update status: `SECURITY_CENTER_UPDATE_STATUS` or System Admin.

---

## Filtering and Sorting

Use the filter bar at the top of the Security Center to narrow the list:

* **Severity** — CRITICAL / HIGH / MEDIUM / LOW
* **Status** — OPEN / IN_PROGRESS / SUPPRESSED / FALSE_POSITIVE / RESOLVED
* **Ecosystem** — MAVEN / NPM / PYPI / etc.
* **Patchability** — Patchable / Non-Patchable / Unknown
* **Search** — free-text search across CVE ID and component name

Click any column header to sort ascending/descending.

---

## Bulk Status Update

1. Select one or more CVEs using the checkboxes.
2. Click **Update Status** in the action toolbar.
3. Choose the new status from the dropdown.
4. Confirm.

All selected CVEs are updated in a single transaction.

---

## Component Detail

Click any component name to open the **Component Detail** side panel, which shows:

* All CVEs for that library with full CVSS breakdown
* License name and compliance status
* AI-generated license risk summary
* Latest available version and deprecation notice
* Full dependency path (direct vs. transitive)

---

## AI Security Insights

If an AI provider is configured (**Settings → AI**), each completed scan generates a one-paragraph AI summary at the top of the Security Center:

* **Security Posture Insight** — overall assessment of current CVE count and severity distribution
* **Security Risk Trend Insight** — comparison with previous scan showing improvement or regression

AI insights are generated once per scan and are not regenerated unless a new scan is submitted.

---

## Patchability

OsWL derives patchability from the `fixVersion` field across all CVEs for a library:

| Status | Condition |
|---|---|
| **PATCHABLE** | At least one CVE has a known fix version |
| **NON_PATCHABLE** | CVEs exist but none have a known fix |
| **UNKNOWN** | No CVEs or CVE data not yet enriched |

---

## Data Sources

CVE data is pulled from two sources and merged:

* **deps.dev** — GHSA advisories, CVE aliases, CVSS scores, and titles via `GetAdvisory`
* **OSV** (Open Source Vulnerabilities) — summaries, fix versions, and **CWE IDs** (`database_specific.cwe_ids`) via `POST https://api.osv.dev/v1/querybatch`

CWE identifiers (e.g. `CWE-79`) appear on the Component Detail panel when OSV provides them.

Enrichment runs automatically after each scan and is refreshed according to the cache policy in **Settings → Cache** (`/api/settings/cache`).
