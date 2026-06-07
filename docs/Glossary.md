# Glossary

All terms used in OsWL — from security concepts to platform-specific vocabulary.

---

## A

**AI Insight**  
An LLM-generated narrative summary produced during scan enrichment. OsWL generates three types per scan: *Security Posture Insight*, *Security Risk Trend Insight*, and *License Risk Trend Insight*. Each is a one-paragraph natural-language assessment. Requires an AI provider to be configured in Settings.

**Audit Log**  
An immutable chronological record of every significant action taken by users or the system (logins, scan submissions, CVE status changes, settings updates, etc.). Accessible to System Admins at Settings → Admin → Audit Logs.

---

## C

**CARGO**  
The Rust package ecosystem, managed by the `cargo` tool. Packages published to [crates.io](https://crates.io).

**CLI (Command-Line Interface)**  
OsWL's language-agnostic REST-based scan submission mechanism. Any build tool or CI pipeline can POST a scan payload using an API key. See [CLI Integration](CLI-Integration.md).

**Component**  
An individual open-source library detected in a scan. Represented in OsWL as a `Library` entity keyed by (name, version, ecosystem). The term *component* is used interchangeably with *dependency* and *library* in the UI.

**CVE (Common Vulnerabilities and Exposures)**  
A publicly disclosed security vulnerability identified by a globally unique ID (e.g. `CVE-2021-44228`). Each CVE is associated with one or more affected library versions and carries a CVSS score.

**CVSS (Common Vulnerability Scoring System)**  
The industry-standard framework for rating the severity of security vulnerabilities. CVSS Base Scores range from 0.0 to 10.0 and are mapped to severity labels (CRITICAL, HIGH, MEDIUM, LOW) in OsWL.

| Score range | OsWL severity |
|---|---|
| 9.0 – 10.0 | CRITICAL |
| 7.0 – 8.9 | HIGH |
| 4.0 – 6.9 | MEDIUM |
| 0.1 – 3.9 | LOW |
| 0.0 | NONE |

---

## D

**deps.dev**  
Google's [Open Source Insights](https://deps.dev) API, which OsWL queries to obtain SPDX license identifiers, latest-version status, and deprecation notices for each scanned library.

**Dependency Path**  
The chain of packages from the root project to a given library. A library may be reachable via multiple paths (direct and/or transitive). OsWL records and displays all resolved paths per component.

**Direct Dependency**  
A library explicitly declared in the project's manifest (e.g. `pom.xml`, `package.json`). Contrast with *Transitive Dependency*.

---

## E

**Ecosystem**  
The package management system a library belongs to. OsWL supports: `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS`.

**Enrichment**  
The asynchronous post-processing phase after a scan is ingested. OsWL queries OSV and deps.dev to populate CVE data, CVSS scores, fix versions, license names, and version status for every detected library.

---

## F

**Fix Version**  
The earliest version of a library in which the associated CVE is patched. Sourced from OSV advisory data. Shown in the Security Center as the recommended upgrade target.

**False Positive**  
A CVE triage status indicating that the vulnerability has been confirmed as not applicable to this project (e.g. the vulnerable code path is not exercised, or the affected feature is not used).

---

## G

**GO**  
The Go programming language module ecosystem. Packages identified by import paths like `github.com/gin-gonic/gin`.

---

## L

**Library**  
OsWL's canonical record for an open-source package, keyed by (name, version, ecosystem). Library records are shared across all projects — if two projects use the same version of `spring-core`, their CVE and license data is stored once.

**License**  
The legal terms under which an open-source library is distributed. OsWL detects licenses using SPDX identifiers and evaluates them against the configured policy.

**License Policy**  
An admin-managed table mapping SPDX license identifiers to compliance statuses (PERMITTED, CAUTION, RESTRICTED). Applied globally to all projects.

**License Status**  
The compliance status assigned to a library's license after policy evaluation:

| Status | Meaning |
|---|---|
| `PERMITTED` | Explicitly allowed by policy |
| `CAUTION` | Requires review — potential copyleft or commercial restrictions |
| `RESTRICTED` | Policy-flagged as incompatible |
| `UNKNOWN` | SPDX identifier not yet fetched or not recognized |

---

## M

**MAVEN**  
The Java/JVM ecosystem managed by Apache Maven. Packages identified as `groupId:artifactId`.

**MFA / 2FA (Multi-Factor / Two-Factor Authentication)**  
An extra login step requiring a one-time password (OTP) sent via email, in addition to the standard email + password. Configurable globally in Settings → Security.

---

## N

**NPM**  
The Node.js/JavaScript package ecosystem managed by the `npm` tool. Packages published to [npmjs.com](https://www.npmjs.com).

**NUGET**  
The .NET package ecosystem. Packages published to [nuget.org](https://www.nuget.org).

---

## O

**OsWL (Open-source Software Watchlist)**  
The platform described in this documentation — an in-house SCA tool for tracking CVEs and license risks across OSS dependencies.

**OSV (Open Source Vulnerabilities)**  
A Google-hosted vulnerability database and API ([osv.dev](https://osv.dev)) focused on open-source packages. OsWL queries OSV for advisory data including affected version ranges and fix versions.

**OTP (One-Time Password)**  
A 6-digit code sent to a user's email address as the second factor in 2FA authentication. In the local development profile, OTP codes appear in the server log as `*** OTP CODE: NNNNNN ***`.

---

## P

**Patchability**  
A derived property indicating whether a fix is available for the vulnerabilities affecting a library:

| Value | Meaning |
|---|---|
| `PATCHABLE` | At least one CVE has a known `fixVersion` |
| `NON_PATCHABLE` | CVEs exist but none have a documented fix |
| `UNKNOWN` | No CVEs, or enrichment not yet complete |

**PAT (Personal Access Token)**  
A secret token generated by a VCS provider (GitHub, GitLab, Bitbucket) that grants API access on behalf of a user account. Used by OsWL for Quick Import and repository browsing.

**Permission**  
A fine-grained access control capability assigned to users via role templates. Examples: `PROJECT_VIEW`, `SCAN_SUBMIT`, `SECURITY_CENTER_UPDATE_STATUS`.

**Project**  
The top-level entity in OsWL representing an application or service under analysis. One project maps to one repository (or one logical unit for CLI-only projects).

**Project Version**  
A snapshot of a project tied to a specific VCS branch. Created automatically by Quick Import.

**PYPI**  
The Python package index. Packages published to [pypi.org](https://pypi.org).

---

## Q

**Quick Import**  
A browser-based workflow to import a project directly from a VCS provider (GitHub / GitLab / Bitbucket) by selecting a repository and branch. OsWL clones the repository, resolves dependencies, and runs a scan — no CLI required.

---

## R

**Risk Level** → see *Severity*

**Risk Trend**  
A time-series visualization showing how the CVE count and license compliance status have changed across the most recent scans of a project.

**Role Template**  
An instance-wide named bundle of **permissions** (e.g. Admin, Developer, Viewer). Assigned to users in **Settings → Admin**. Controls *what features* a user may use across OsWL. **Not** the same as project membership. See [Authorization layers](Authorization-Layers.md).

**Project membership**  
A row in `project_members` linking a user to a specific project. Controls *which projects* a user may open. Works together with role-template permissions. Project membership roles (`ADMIN` / `MEMBER`) are separate from template names.

**System administrator**  
A user flag set at initial setup. Can manage users, role templates, and audit logs, and can access all projects regardless of membership. Distinct from a role template named "Admin".

**RUBYGEMS**  
The Ruby package ecosystem managed by the `gem` tool. Packages published to [rubygems.org](https://rubygems.org).

---

## S

**SCA (Software Composition Analysis)**  
The practice of identifying and assessing OSS components used in a software project — particularly for security vulnerabilities and license compliance. OsWL is an SCA platform.

**Scan**  
A single invocation of the dependency analysis pipeline for a project version. A scan consists of a list of detected components and their resolved CVE and license data. Triggered by Quick Import or CLI (`POST /api/scan`).

**Scan Status**

| Status | Description |
|---|---|
| `PENDING` | Received; awaiting processing |
| `SCANNING` | Parsing dependency manifests |
| `ANALYZING` | Enriching CVE and license data |
| `COMPLETED` | Fully enriched; results are final |
| `FAILED` | An unrecoverable error occurred |

**Security Center**  
The main vulnerability management page for a project. Displays all CVEs affecting scanned components, with filtering, sorting, status management, and AI insights.

**Severity**  
A classification of a CVE's risk level based on CVSS score. OsWL uses: CRITICAL, HIGH, MEDIUM, LOW, NONE.

**Single-Session Enforcement**  
OsWL allows only one active session per user. A new login from a different browser/device invalidates the previous session.

**SPDX (Software Package Data Exchange)**  
An open standard for communicating software bill of materials (SBOM) information, including license identifiers. OsWL uses SPDX identifiers (e.g. `MIT`, `Apache-2.0`, `GPL-3.0-only`) to represent library licenses.

**SPDX Identifier**  
A standardized short string identifying a specific software license. Examples: `MIT`, `Apache-2.0`, `GPL-3.0-only`. The full list is maintained at [spdx.org/licenses](https://spdx.org/licenses/).

---

## T

**Transitive Dependency**  
A library that is not directly declared in the project's manifest but is pulled in as a dependency of a direct dependency (or deeper). Contrast with *Direct Dependency*.

**Trusted Device**  
A browser that has been marked as trusted after a successful 2FA OTP verification. Trusted devices skip the OTP step for subsequent logins within the configured trust period (default: 30 days).

---

## V

**VCS (Version Control System)**  
A source code hosting platform such as GitHub, GitLab, or Bitbucket. OsWL connects to VCS providers via Personal Access Tokens for Quick Import.

**VCS Connection**  
A stored, encrypted PAT + provider configuration that OsWL uses to authenticate against a VCS API. Managed in Settings → VCS.

**Version Diff**  
A comparison of two scan results showing which components were added, removed, or changed between versions.

**Vulnerability**  → see *CVE*

---

## Z

**Zero-Day**  
A vulnerability that is publicly known but for which no official patch yet exists (fix version is null). OsWL marks such CVEs as `NON_PATCHABLE` until a fix version is published.
