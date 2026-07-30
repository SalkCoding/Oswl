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

Click any column header to sort ascending/descending. A dedicated **Sort** selector controls the base ordering: **Risk (default)** (highest severity first), **Name**, or **License risk**.

---

## Bulk Status Update

1. Select one or more CVEs using the checkboxes.
2. Click **Update Status** in the action toolbar.
3. Choose the new status from the dropdown.
4. Confirm.

All selected CVEs are updated in a single transaction.

---

## Export (v1.0.4)

The **Export** dropdown in the action toolbar produces standards-based artifacts from the latest completed scan:

| Item | Endpoint | Format |
|---|---|---|
| SBOM (CycloneDX) | `GET /api/projects/{projectId}/sbom` | CycloneDX 1.6 JSON |
| VEX | `GET /api/projects/{projectId}/vex` | CycloneDX VEX — carries your triage decisions |
| SARIF | `GET /api/projects/{projectId}/sarif` | SARIF 2.1.0, uploadable to GitHub code scanning |
| Compliance report | `GET /security-center/compliance-report` | Print-ready HTML (use *Print → Save as PDF*) |
| CVE / license CSV | — | Current filtered view |

Bulk actions also include **Create upgrade PR**, which opens one pull request bumping every selected component to its fix version.

See [What's New in v1.0.4](Whats-New-v1.0.4.md) for details.

---

## Air-gapped Mode

When OsWL is running in air-gapped (offline) mode, the Security Center index, the printable Security Report, and the Compliance Report show an **offline definitions** banner:

> Offline mode — analyzed with vulnerability definitions as of 2026-07-24.

The date is the oldest upstream source as-of date in the loaded offline snapshot (OSV, deps.dev advisories/versions, EPSS, CISA KEV). It is shown so auditors can see how fresh the underlying vulnerability data was at scan time. Freshness thresholds and snapshot import are managed from **Settings → Administration → Offline Snapshot**.

---

## Component Detail

Click any component name to open the **Component Detail** side panel, which shows:

* A short description of what the library actually is, plus homepage/source-repo links, from the deps.dev project record
* All CVEs for that library with full CVSS breakdown, grouped under a **Security Issues** heading set apart from the badges above it
* License name and compliance status
* AI-generated license risk summary
* Latest available version and deprecation notice
* Full dependency path (direct vs. transitive)
* **OpenSSF Scorecard** score, and **known-malicious** / **typosquat-risk** badges when the supply-chain heuristics flag the package (v1.0.4)
* A **Create Jira issue** button when the Jira integration is configured (v1.0.4). Once created, the badge links straight to the Jira issue; if creation fails, the reason appears in a toast matching the style used everywhere else in the app

Long CVE descriptions are truncated in the collapsed row; clicking the row expands it and shows the full advisory text, so you can always see *why* something is a vulnerability.

The panel also offers remediation actions: **Apply Patch (Create PR)** and **Defer**. A deferral records a reason and an expiry preset (1 week / 1 / 3 / 6 months, a custom date, or indefinite). A custom expiry must be a **future date** — past or malformed dates are rejected with HTTP 400, and the date picker only allows tomorrow onward.

After a PR/MR is created, the success panel shows a prominent **View PR/MR** button plus a collapsible **"What changed?"** summary — the patched manifest file path and the version bump (e.g. `pom.xml`: `log4j-core 2.14.1 → 2.17.0`).

When a component's deferral period expires (nightly scheduler), the deferral is cleared and the Security Center shows a dismissible **🦉 owl reminder banner** for components whose deferral ended within the last 7 days, prompting a re-review.

---

## AI Security Insights

If an AI provider is configured (**Settings → AI**), scan enrichment generates free-text AI insights. Since v1.0.4, all scan-level insights are produced by a single combined AI call per scan:

* **Security Posture Insight** — overall assessment of current CVE count and severity distribution, shown as a one-paragraph **AI Insight** summary at the top of the Security Center
* **Security Risk Trend Insight** — comparison with the previous scan showing improvement or regression, shown on the [Risk Trend](Risk-Trend.md) page

Insights are generated once per scan. They are backfilled when an AI provider is first activated, regenerated for recent scans when the AI prompt language changes, and can be refreshed on demand from the License page.

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

Since v1.0.4, two prioritization feeds are merged in as well:

* **CISA KEV** — the *Known Exploited Vulnerabilities* catalogue. A KEV listing means confirmed in-the-wild exploitation and outranks a raw CVSS score for triage order.
* **EPSS** (FIRST.org) — probability of exploitation in the next 30 days, used to rank everything that is not on KEV.

Test- and dev-only dependencies are tagged with their scope and can be hidden from the list, so production risk stands out. They are tagged rather than dropped, so nothing disappears from the SBOM.

Enrichment runs automatically after each scan and is refreshed according to the cache policy in **Settings → Cache** (`/api/settings/cache`).
