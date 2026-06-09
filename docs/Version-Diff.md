# Version Diff

The Version Diff page lets you compare two scan results side-by-side — showing which components were added, removed, or changed between versions.

URL: `/projects/{id}/version-diff`

---

## How to Use

1. Open a project and navigate to **Version Diff** in the sidebar.
2. Select a **Base** scan (the older version) from the left dropdown.
3. Select a **Compare** scan (the newer version) from the right dropdown.
4. Click **Compare**.

OsWL computes the diff and displays it in three sections:

---

## Diff Sections

### Added Components

Components present in the **Compare** scan but **not** in the Base scan.

These are new dependencies that were introduced between the two versions. Pay attention to any that have CRITICAL or HIGH CVEs immediately — they are new attack surface.

### Removed Components

Components present in the **Base** scan but **not** in the Compare scan.

These dependencies were dropped. If any of them had known CVEs, their removal is a positive signal.

### Changed Components

Components present in **both** scans but with a **different version**.

| Column | Description |
|---|---|
| **Component** | Library name |
| **Base Version** | Version in the Base scan |
| **Compare Version** | Version in the Compare scan |
| **CVE Delta** | Change in CVE count (e.g. −2 CRITICAL means two critical CVEs were resolved) |

An upgrade that resolves CVEs shows a negative CVE delta (green). A downgrade or a version bump that introduces new CVEs shows a positive delta (red).

---

## Use Cases

* **Pre-release review**: Compare the release candidate against the last release to verify no new high-severity CVEs were introduced.
* **Dependency audit**: Compare two feature branches to understand the risk impact of dependency changes in a PR.
* **Remediation verification**: Confirm that upgrading a specific library actually resolved the expected CVEs.
* **Regression detection**: Identify accidental version downgrades introduced by dependency resolution conflicts.

---

## Tips

* The diff is computed on-demand from stored scan data — no re-scanning is required.
* If both scans have the same component list with no version changes, all three sections will be empty (identical versions).
* Use [Scan History](Scan-History.md) to find the scan IDs you want to compare.
