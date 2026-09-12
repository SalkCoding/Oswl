# Security Center

Stored lookup outcomes and OSV common-fix assessments reject duplicate JSON keys (including nested keys), trailing JSON values and numeric/boolean coercion into text fields. Ambiguous lookup outcomes become STORAGE/UNAVAILABLE; ambiguous common-fix assessments become STORAGE_ERROR and cannot supply a common fix version. Reading these records preserves the original stored text and individual finding fixes; a successful new lookup can still replace the live cache.

Live GitHub Advisory responses also reject duplicate JSON fields and trailing JSON values before interpreting a page. An ambiguous page leaves the lookup incomplete. Independently confirmed findings from earlier pages remain available, with fix candidates withheld because coverage is incomplete. This does not certify the provider response or change offline snapshot interpretation. If the same advisory is both active and withdrawn within or across response pages, the lookup stays incomplete and fixes are withheld. Findings observed in active entries remain available; OsWL does not choose a current revision from conflicting lifecycle states. Consistently withdrawn entries remain excluded. For npm, Cargo, Go, PyPI, Maven and NuGet, installed versions are validated with the existing ecosystem comparator before either a live empty response or a complete empty snapshot can establish coverage. Unsupported concrete version syntax remains unresolved even when no advisories are returned. This does not add version-ordering support for other ecosystems. OSV also checks concrete version syntax for npm, Cargo/crates.io, Go, PyPI, Maven and NuGet before marking results complete, including empty live and snapshot results. Invalid supported-version syntax withholds individual and common fix guidance while retaining available findings and the stored snapshot candidate. This is separate from source freshness and does not add comparators for other ecosystems. OSV batch, continuation and advisory-detail responses reject duplicate JSON fields and trailing JSON values before interpretation. An unreadable first batch stays unresolved; an unreadable detail retains only its queried advisory identity. A failed continuation retains earlier findings and their individual evidence but withholds the common fix and completed coverage.

For a complete GitHub Advisory lookup, fix selection considers the provider-supplied candidates from every validated range of the same advisory, including ranges that do not contain the installed version. A candidate must be newer than the installed version and outside every collected range for that advisory. For example, if 2.0.0 is still affected by another interval but the supplied 3.0.0 clears all intervals, 3.0.0 remains eligible. This does not turn unaffected intervals into installed findings or establish a package-wide safe target. See the [GitHub field definitions](https://docs.github.com/en/graphql/reference/security-advisories#securityvulnerability).

A database integration check feeds the online client synthetic overlapping ranges, packages its selected result in an explicit test ZIP, imports it into H2 and an isolated PostgreSQL database, and reads it with the offline client. Fresh source metadata preserves the same advisory, severity and 3.0.0 fix. Eight-day-old or missing dates preserve the finding and stored candidate but withhold the offline fix; an absent version key has no completed lookup. The dates are simulated. This verifies the importer/client path, not CLI collection or live GitHub access.

Patch filtering ranks component/library IDs once, then reads scalar evidence in batches and loads only the selected component entities. Entity views and filtering share lookup-time and patchability rules, including unknown coverage, CPE candidates, missing severity and whitespace-only fix versions. In a single local H2 measurement with 50,000 components and one finding each, filtering loaded 1–305 entities instead of about 100,000 and returned exact 25,000-match pagination in 1.1–1.8 seconds. The sampled full JVM heap, including the in-memory database, was about 460 MiB under a 512 MiB limit. These are individual observations, not production latency percentiles or a memory guarantee; concurrent requests, dense finding sets and production PostgreSQL scale require further validation.

An additional local H2 check used 5,000 components with ten findings each and four simultaneous read-only service calls for pages 0, 1, 24 and 25. Each call retained the exact 2,500-match total; the three nonempty pages contained 300 unique components. Mixed libraries with one CPE candidate and nine package findings stayed excluded from patchable results. In this single run the calls took 5.0–5.3 seconds, loaded 3,608 entities in total and sampled about 379 MiB of full JVM heap. This is not an HTTP concurrency test, a production PostgreSQL benchmark or a concurrent-update guarantee.

Severity filters use the same candidate exclusion as the displayed counts. The unscored filter includes both missing severity and NONE. Candidates remain visible when no severity filter is selected. Patch filters use the same assessment as component rows: incomplete lookup or unresolved CPE candidates cannot match patchable/non-patchable filters, while missing severity does not discard an individual fix. Version-currency filters apply only to components without findings. Patchability describes available individual fixes, not proof of a common safe upgrade target. When patch filters are selected, the service evaluates matching rows in batches of 100 before calculating the requested page and exact total. This requires examining all rows that satisfy the other filters and can cost more on large scans. Risk sorting compares non-candidate counts in this order: critical, high, medium, low, unscored, then CPE candidate count; each count is descending. Name and component ID break ties. A large number of lower-severity findings cannot outweigh a higher-severity count. Candidates remain visible but their original severity does not increase the severity ranking. A zero ranking is not proof of successful lookup or safety.

Component rows, detail headers, print rows and CSV severity counts also exclude CPE candidates and display a separate candidate count. Candidate-only components retain unknown patchability and do not show a no-findings badge. Null severity and NONE findings share the unscored bucket. CSV appends `CPE Match Review Candidates` after the existing columns; consumers that require an exact column count must accept the added column. These component views still read the shared library cache and may differ from preserved historical summaries.

Scan summary severity totals and the organization KEV aggregate exclude CPE findings that require matching review. A separate candidate count appears in the security center, project cards, organization views and risk trends. Package evidence on the same finding retains its existing classification. V45 preserves the separate count on new archives; older archives without a preserved assessment or this count retain their original totals with an unknown-separation notice. They are not silently reclassified or set to zero. Detail-row classifications and shared-cache versus historical-data distinctions remain separate from these summary totals.

Patchability remains Unknown while vulnerability lookup coverage is incomplete; individual findings and stored fix versions remain visible. With completed coverage, Patchable means at least one active finding has a stored fix, not that a single version resolves every finding. Non-Patchable means no fix is recorded for the active findings, not proof that no upstream fix exists. The common upgrade recommendation is assessed separately.

The scan-evidence notice identifies the selected scan, its recorded time and registered repository. An older scan without a preserved assessment cannot establish whether its input was a sample, manual submission or a real repository scan. Do not infer a clean scan from zero findings; use Quick Import on the actual repository or submit a new CLI scan. Component details show the current shared lookup cache, which can differ from the original scan. A preserved assessment is a historical record, not a guarantee that every source completed successfully.

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
| Compliance report | `GET /projects/{projectId}/security-center/compliance-report` | Print-ready HTML (use *Print → Save as PDF*) |
| CVE / license CSV | — | Component summary for the selected completed scan (latest completed scan by default) |

The component CSV appends `Vulnerability Lookup Complete`, `Lookup Outcomes`, and `Lookup Attempt At` after the existing columns. `No` means zero CVEs cannot establish a completed lookup. `Yes` follows the same completion rule as the component screen and is not a safety guarantee. Source outcomes are sorted `SOURCE=STATUS` pairs; absent historical metadata stays blank. Legacy cache records without source outcomes are `No` even when a fetch timestamp exists. The next analysis retries them, including with permanent caching. The attempt time is the stored local timestamp, not the advisory dataset's publication date or freshness guarantee. Consumers that require a fixed column count must accommodate these three additional columns.

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

When CVEs are present and no distinct documented fix target is available, automatic PR creation does not fall back to the latest release. A latest release is not proof of a security fix. Maintenance PRs using the latest release require no CVEs and a confirmed outdated status; an unknown version status is insufficient.

Immediately before a single or batch PR changes VCS state, OsWL rechecks the installed and proposed versions through OSV. A security target must match the common fix derived from current OSV originals, cover the stored findings without fix conflicts, and return no findings for the target. The target also needs resolved deps.dev version data without advisory IDs, plus complete, empty GitHub Advisory coverage when configured or previously required. Offline operation needs the corresponding target-version snapshot entries; missing or stale coverage withholds the PR. CPE/repository identities and ecosystems without supported version evidence remain unverified. This checks the package version and queried sources, not the complete dependency graph produced by an upgrade. Screens and PR candidate selection now use the current OSV common assessment instead of the highest-severity individual fix. Missing, expired, incomplete, uncovered or conflicting evidence leaves the common candidate unknown while preserving individual advisory fixes. The candidate can still be rejected by the final target check.

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

## Built-in source inspection coverage

Secret and IaC file-reading or directory-traversal failures now retain an incomplete marker alongside any findings already collected. Missing secret rules also produce an incomplete marker. These existing marker types prevent a complete gate result even when secret-finding blocking is disabled. An accessible empty directory still returns zero findings. This change does not account for every excluded file, scan budget, disabled scanner or missing source in other ingestion paths. Reaching the built-in 300-finding limit also records one incomplete marker in addition to the retained findings. Exactly 300 is treated as incomplete because the scanner stops at that limit without proving it reached the end of every input. The limit does not silently discard already collected findings.

An otherwise applicable file larger than 1,000,000 bytes also records incomplete coverage instead of silently returning zero findings. Files at or below that limit remain eligible for inspection. Existing binary-extension exclusions and IaC target classification are applied before this size check. This is a per-file limit; total byte and time budgets remain separate work.

Secret inspection treats malformed or truncated UTF-8 as incomplete coverage. It does not replace invalid bytes and claim a successful read. Findings from other readable files remain available. Valid UTF-8, including an explicitly encoded replacement character, remains readable. Other source encodings are not automatically guessed or converted.
