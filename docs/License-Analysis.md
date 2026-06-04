# License Analysis

The License Analysis page gives a component-by-component view of the open-source licenses detected in the latest scan and flags any that conflict with your policy.

URL: `/projects/{id}/license`

---

## How License Data is Collected

OsWL queries **deps.dev** (Google's Open Source Insights API) for each scanned library to obtain the SPDX license expression. This is matched against a global license policy to determine the compliance status.

---

## License Status

Each component is assigned one of four license statuses:

| Status | Meaning | Badge color |
|---|---|---|
| **PERMITTED** | License is explicitly allowed by policy | Green |
| **CAUTION** | License requires review (e.g. weak copyleft) | Yellow |
| **RESTRICTED** | License is flagged as incompatible (e.g. strong copyleft used in proprietary code) | Red |
| **UNKNOWN** | SPDX identifier not yet fetched or not recognized | Gray |

The overall project license badge shown on the Projects dashboard reflects the **worst** status among all components.

---

## License Policy

The license policy is a list of SPDX identifiers mapped to a status. System Admins and users with `LICENSE_POLICY_MANAGE` permission can manage policy entries.

### Editing Policy Entries

1. Open the License Analysis page for any project.
2. Click **Manage Policy** (requires `LICENSE_POLICY_MANAGE`).
3. Add, edit, or remove SPDX ↔ status mappings.

Policy entries apply globally across **all** projects.

### Common SPDX Identifiers

| SPDX ID | License name | Typical policy |
|---|---|---|
| `MIT` | MIT License | PERMITTED |
| `Apache-2.0` | Apache License 2.0 | PERMITTED |
| `BSD-2-Clause` | BSD 2-Clause | PERMITTED |
| `BSD-3-Clause` | BSD 3-Clause | PERMITTED |
| `ISC` | ISC License | PERMITTED |
| `LGPL-2.1-only` | GNU LGPL 2.1 | CAUTION |
| `LGPL-3.0-only` | GNU LGPL 3.0 | CAUTION |
| `MPL-2.0` | Mozilla Public License 2.0 | CAUTION |
| `GPL-2.0-only` | GNU GPL 2.0 | RESTRICTED |
| `GPL-3.0-only` | GNU GPL 3.0 | RESTRICTED |
| `AGPL-3.0-only` | GNU AGPL 3.0 | RESTRICTED |
| `SSPL-1.0` | Server Side Public License | RESTRICTED |

---

## Filtering

Use the filter bar to narrow the license view by:

* **Status** — PERMITTED / CAUTION / RESTRICTED / UNKNOWN
* **Ecosystem** — MAVEN / NPM / PYPI / etc.
* **Search** — free-text across component name and SPDX identifier

---

## AI License Insights

If an AI provider is configured, each completed scan generates:

* **License Risk Trend Insight** — narrative summary comparing license compliance against the previous scan.
* **Per-library AI Summary** — a one-sentence compliance risk statement visible in the Component Detail panel.

---

## Understanding Version Status

The License Analysis page also surfaces two additional signals from deps.dev:

| Signal | Description |
|---|---|
| **Not Latest** | A newer stable version exists — consider upgrading |
| **Deprecated** | The package version is officially deprecated; the deprecation reason is shown |

These signals are informational and do not affect the license status.

---

## Exporting reports

Users with **`LICENSE_EXPORT`** (or System Admin) can download:

* **NOTICE** file — attribution text for distributions  
* **SPDX SBOM** — machine-readable bill of materials  

Exports require **project membership** as well as the permission. See [Authorization layers](Authorization-Layers.md).
