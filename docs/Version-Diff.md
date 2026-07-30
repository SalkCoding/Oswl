# Version Diff

The Version Diff page lets you compare two scan results side-by-side — showing which components were added, removed, updated, or became new threats between versions.

URL: `/projects/{id}/version-diff`

---

## How to Use

1. Open a project and navigate to **Version Diff**.
2. The page pre-selects the two most recent completed scans — the older one as **From** (left) and the newer one as **To** (right).
3. To compare a different pair, pick another scan from either dropdown. Each entry shows the version label and the scan date.

The comparison updates immediately when you change a selection — there is no separate compare button. Only completed scans are listed, and a scan without an explicit version label appears as its scan date.

Summary cards at the top show the per-category counts: **Added**, **Removed**, **Updated**, and **New Threats**, along with the total number of changes found.

---

## AI Diff Insight

When AI is enabled, a one-line AI summary of the diff is shown above the table.

During post-scan AI enrichment, OsWL generates the version-diff insight as part of a single combined AI insights call (together with the security posture and trend insights) and stores it on the scan. The page reuses that stored insight when you compare the scan against the same base scan it was generated from; for any other pair, the insight is generated on demand. If AI is disabled or the call fails, the insight is simply not shown.

---

## Diff Sections

Filter tabs (**ALL** / **Added** / **Removed** / **Updated** / **New Threats**) let you narrow the list. Each row shows the **From** scan on the left and the **To** scan on the right, with the component name, version, and a risk badge (highest known severity) on each side, plus a change-type tag on the right.

### Added Components

Components present in the **To** scan but **not** in the From scan, with no known vulnerabilities.

These are new dependencies that were introduced between the two versions. Added components that already carry known CVEs are classified as **New Threats** instead — check those first, as they are new attack surface.

### Removed Components

Components present in the **From** scan but **not** in the To scan.

These dependencies were dropped. If any of them had known CVEs, their removal is a positive signal.

### Updated Components

Components present in **both** scans but at a **different version**, without an increase in severity.

### New Threats

Components whose risk grew between the two scans:

* a newly added component that already carries known vulnerabilities,
* a version update that raises the highest severity, or
* the same component version whose assessed severity increased (e.g. a CVE published after the base scan).

> When a library appears in **multiple versions within the same scan** (a diamond dependency — e.g. two transitive paths pulling different major versions of the same package), each version is tracked and diffed independently rather than collapsed into a single row.

---

## Use Cases

* **Pre-release review**: Compare the release candidate against the last release to verify no new high-severity CVEs were introduced.
* **Dependency audit**: Compare two feature branches to understand the risk impact of dependency changes in a PR.
* **Remediation verification**: Confirm that upgrading a specific library actually resolved the expected CVEs.
* **Regression detection**: Identify accidental version downgrades introduced by dependency resolution conflicts.

---

## Tips

* The diff is computed on-demand from stored scan data — no re-scanning is required.
* If both scans have the same component list with no meaningful changes, the page shows **No changes found**.
* In offline (air-gapped) mode, risk levels reflect the imported vulnerability definitions; the definition as-of date is shown on the [Scan History](Scan-History.md) and Security Center pages. Comparing scans analyzed against different definition dates can surface New Threats even when the dependency tree itself did not change.
