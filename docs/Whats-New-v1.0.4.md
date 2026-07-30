# What's New in v1.0.4

v1.0.4 is the compliance-and-workflow release. It adds standards-based exports (CycloneDX SBOM, VEX, SARIF), a CI/CD security gate, continuous monitoring, an organization-wide dashboard, supply-chain heuristics, air-gapped operation, and Japanese localization.

Everything below is available in the self-hosted build — no separate edition or licence key.

---

## Compliance exports

### CycloneDX SBOM (1.6)

| | |
|---|---|
| **Endpoint** | `GET /api/projects/{projectId}/sbom` |
| **UI** | Security Center → **Export** dropdown → *SBOM (CycloneDX)* |
| **Format** | CycloneDX 1.6 JSON, `application/vnd.cyclonedx+json` |

The SBOM is generated from the project's latest completed scan. Every component carries its `purl`, resolved licence (SPDX id or expression), and scope. Metadata records the tool, the scan timestamp, and the project/version as the root component.

### VEX

| | |
|---|---|
| **Endpoint** | `GET /api/projects/{projectId}/vex` |
| **UI** | Security Center → **Export** dropdown → *VEX* |

VEX carries your triage decisions, not just the raw findings. Each vulnerability maps to a CycloneDX analysis state:

| OsWL status | VEX state | Justification |
|---|---|---|
| Open / Reviewing | `exploitable` | — |
| Ignored | `not_affected` | recorded from the ignore reason |
| Deferred | `in_triage` | includes the defer-until date |
| Fixed | `resolved` | — |

This is what auditors and downstream consumers want: a machine-readable answer to *"you ship this vulnerable library — are you actually affected?"*

### SARIF (2.1.0)

| | |
|---|---|
| **Endpoint** | `GET /api/projects/{projectId}/sarif` |
| **UI** | Security Center → **Export** dropdown → *SARIF* |
| **Format** | SARIF 2.1.0, `application/sarif+json` |

Schema-compatible with `github/codeql-action/upload-sarif`, so findings land in the GitHub **Security** tab. Each CVE becomes a rule with `security-severity`; each affected component becomes a result with a manifest location and `partialFingerprints`. Deferred and ignored findings are emitted as `suppressed` so they don't re-alert.

```yaml
- name: Upload OsWL SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: oswl.sarif
```

### SBOM import

| | |
|---|---|
| **Endpoint** | `POST /api/sbom/import` (multipart) |
| **UI** | Quick Import → **Import SBOM** |

Upload a third-party CycloneDX file to scan components you don't build yourself — vendor deliverables, container base images, or an SBOM produced by another tool. Imported components are enriched exactly like scanned ones.

### Compliance report pack

`GET /security-center/compliance-report` renders a print-ready report: component inventory, licence obligations, NOTICE text, and open findings by severity. Use your browser's *Print → Save as PDF*. The preview no longer opens the print dialog automatically, so you can review it before exporting.

---

## CI/CD security gate

`POST /api/scan/gate` — authenticated with the same CLI API key as scan ingest, so the project is taken from the key.

The response is a machine-readable verdict; map `exitCode` to your job's exit code (`0` pass, `1` fail).

Defaults (override per request, or via environment):

| Setting | Env | Default |
|---|---|---|
| Fail at this severity or higher | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| Fail on any CISA KEV-listed CVE | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| Fail at EPSS ≥ *n* (negative disables) | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| Fail on RESTRICTED-licence components | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| Only consider findings new since the last scan | `OSWL_GATE_ONLY_NEW` | `true` |

`only-new` is what makes the gate adoptable on an existing codebase: the previous completed scan becomes the baseline, so pre-existing debt never blocks a merge — only newly introduced risk does.

When the request supplies a GitHub target, the verdict is also published as a **Check Run** and a PR comment.

See [CLI Integration](CLI-Integration.md) for the full request shape and pipeline examples.

---

## Continuous monitoring & CVE alerts

A nightly job re-queries OSV for every project's latest completed scan, so a CVE published *after* your last scan still reaches you.

| Setting | Env | Default |
|---|---|---|
| Enabled | `OSWL_MONITORING_ENABLED` | `true` |
| Schedule (Spring cron) | `OSWL_MONITORING_CRON` | `0 0 3 * * *` (03:00 daily) |

New vulnerabilities raise a dashboard alert on the project card and email project members. Acknowledge with `POST /projects/{projectId}/cve-alerts/acknowledge`.

---

## Organization dashboard

`/org-dashboard` rolls every project up into one CISO-level view: severity totals, worst-project ranking, KEV-listed CVE count, and licence warnings.

Access requires the new `ORG_DASHBOARD_VIEW` permission (or `SYSTEM_ADMIN`). The entry point appears in the top bar on the projects, project-detail, and version-diff screens once the permission is granted.

---

## Prioritization signals

* **CISA KEV** — vulnerabilities on the *Known Exploited Vulnerabilities* catalogue are flagged first. Being on KEV means confirmed in-the-wild exploitation, which outranks a raw CVSS score for triage order.
* **EPSS** — the FIRST.org exploit-prediction score ranks everything else by the probability of exploitation in the next 30 days.
* **Dependency scope** — test- and dev-only dependencies are tagged, and can be hidden from the findings list so production risk stands out. They are tagged rather than dropped, so nothing disappears from the SBOM.

---

## Supply-chain heuristics

`SupplyChainHeuristicsService` flags two component-level risks, shown as badges on the component detail page:

* **Known-malicious packages** — matched against advisory feeds.
* **Typosquatting risk** — Levenshtein distance against a popular-package list catches `expres`, `lodahs`, and friends.

The component detail page also surfaces the **OpenSSF Scorecard** score from deps.dev, so an unmaintained-but-not-yet-vulnerable dependency is visible before it becomes an incident.

---

## Batch upgrade PRs

Security Center → bulk actions → **Create upgrade PR** opens a single pull request that bumps every selected component to its fix version, patching the manifest in place (Renovate-lite, scoped to security fixes).

---

## Jira integration

Configure once at Settings → Integrations (`GET /api/settings/jira`), then create an issue straight from a finding: `POST /projects/{projectId}/components/{componentId}/jira-ticket`. The issue body is Atlassian Document Format with the CVE, severity, affected version, and fix version; the component detail page then links to the created ticket.

---

## Air-gapped mode

For networks with no outbound internet. When `OSWL_AIRGAPPED_ENABLED=true`, all vulnerability and threat-intel lookups (OSV, deps.dev, EPSS, KEV) are served from an imported offline snapshot instead of live APIs — no outbound HTTP is attempted.

| Action | Endpoint |
|---|---|
| Import a bundle | `POST /api/admin/snapshot/import` (multipart, `SYSTEM_ADMIN`) |
| Export a bundle | `GET /api/admin/snapshot/export` |
| Bundle status | `GET /api/admin/snapshot` |

Export on a connected machine, carry the bundle in, import it. Components absent from the snapshot resolve as *no data* rather than *no vulnerabilities*. Combined with the [embedded AI](Embedded-AI.md) sidecar, scanning, triage, and AI analysis all work with the network cable unplugged.

---

## Operations

| Capability | How |
|---|---|
| **Prometheus metrics** | `/actuator/prometheus` — exposed via micrometer, admin-gated |
| **Health / info** | `/actuator/health`, `/actuator/info` |
| **Flyway migrations** | Opt-in with `OSWL_FLYWAY_ENABLED=true` (`baseline-on-migrate`); default remains `ddl-auto` — see [Database schema](Database-Schema.md) |
| **OIDC single sign-on** | Uncomment the `spring.security.oauth2.client` block in `application-prod.yaml` and set `OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` (Okta, Entra ID, any OIDC provider). The login page shows the SSO button only when a provider is registered. |
| **Audit-log SIEM export** | `GET /api/admin/audit-logs/export?format=jsonl\|cef` — reuses the audit-log filters; requires `AUDIT_LOG_EXPORT`. The export itself is audited. |

---

## New permissions

Grant these in Administration → Role templates:

| Permission | Grants |
|---|---|
| `ORG_DASHBOARD_VIEW` | View the organization dashboard |
| `AUDIT_LOG_VIEW` | View the audit log |
| `AUDIT_LOG_EXPORT` | Export the audit log for SIEM |
| `SETTINGS_JIRA_MANAGE` | Manage the Jira integration |
| `SETTINGS_SNAPSHOT_MANAGE` | Manage offline snapshot bundles |

Existing role templates are unchanged, so these start ungranted — assign them deliberately.

---

## New ecosystems

| Ecosystem | Manifest | purl |
|---|---|---|
| PHP | `composer.lock` | `pkg:composer/<vendor>/<package>@<version>` |
| C/C++ | `conan.lock` (Conan 2.x) | `pkg:conan/<name>@<version>` |

Both are parsed by the existing manifest pipeline, so branch import, transitive paths, and enrichment behave identically to the other ecosystems.

---

## Japanese localization

The UI, e-mail templates, landing page, and open-source notices now ship in **English, Korean, and Japanese**. Switch with the language selector in the top bar (or `?lang=ja`).

AI output follows the operator, not the server: the locale of the person who started the scan is captured on the scan record, and AI Insight answers come back in that language. Prompt templates have Korean and Japanese overlays (`ai/prompts_ko.properties`, `ai/prompts_ja.properties`); set the template locale at Settings → AI.

---

## Upgrade notes

* **Schema** — two `libraries` columns are added (`malicious`, `typosquat_risk`), both `NOT NULL DEFAULT false`, so `ddl-auto=update` applies them cleanly to a populated database. No manual migration needed.
* **Nothing is enabled behind your back** — the gate, air-gapped mode, Flyway, and OIDC are all opt-in. Continuous monitoring is the one exception: it defaults to on, and can be disabled with `OSWL_MONITORING_ENABLED=false`.
* **New permissions default to ungranted**, so existing users see no change until you grant them.
