# OsWL Documentation

Welcome to the **OsWL** (Open-source Software Watchlist) documentation hub.

OsWL is an in-house **SCA (Software Composition Analysis)** platform that gives your team a single place to track CVE vulnerabilities and license compliance across all OSS dependencies — from a single microservice to an entire portfolio of products.

---

**English** | [한국어](../ko/Home.md) | [日本語](../ja/Home.md)

## Navigation

For new developers: [Developer onboarding (Korean)](../ko/Developer-Onboarding.md) explains the current architecture, data model, feature flows, and code entry points.

| Page | What you'll find |
|---|---|
| [What's New in v1.0.4](Whats-New-v1.0.4.md) | SBOM / VEX / SARIF export, CI gate, continuous monitoring, org dashboard, air-gapped mode |
| [Getting Started](Getting-Started.md) | System requirements, installation, setup wizard, first login |
| [User Guide](User-Guide.md) | Projects dashboard, scan cards, trash, filters |
| [Quick Import](Quick-Import.md) | Connecting GitHub / GitLab / Bitbucket, branch import |
| [CLI Integration](CLI-Integration.md) | API keys, scan payload format, pipeline integration |
| [Scan History](Scan-History.md) | Per-project scan list — status, component count, submitter |
| [Embedded AI](Embedded-AI.md) | Local Qwen3 LLM sidecar (llama.cpp) — no cloud account needed |
| [Security Center](Security-Center.md) | CVE list, severity ranking, status updates, bulk actions |
| [License Analysis](License-Analysis.md) | SPDX detection, policy entries, risk badges |
| [Risk Trend](Risk-Trend.md) | Historical charts, AI insights, scan limit |
| [Version Diff](Version-Diff.md) | Comparing two versions side-by-side |
| [Administration](Administration.md) | User management, roles, audit logs, security & SMTP settings |
| [Authorization layers](Authorization-Layers.md) | Role templates vs project membership (who can access what) |
| [Project access control](Project-Access-Control.md) | Technical ACL reference for developers |
| [Production deployment](Production-Deployment-Checklist.md) | Pre-launch checklist for `prod` profile |
| [Backup and restore](Backup-And-Restore.md) | Backup scope, restore procedure, verification |
| [UI states checklist](Ui-States-Checklist.md) | Developer reference for asynchronous UI states |
| [Database schema](Database-Schema.md) | `ddl-auto` strategy and `src/main/resources/db/` migrations |
| [Scan API security](Scan-Api-Security.md) | How CLI scan submission is protected |
| [API Reference](API-Reference.md) | Full REST endpoint catalogue |
| [Glossary](Glossary.md) | Definitions of all OsWL terms |

> Korean documentation lives in [`docs/ko/`](../ko/Home.md); Japanese documentation lives in [`docs/ja/`](../ja/Home.md).

---

## Core Concepts at a Glance

```
┌──────────────────────────────────────────────────────────────┐
│  Project                                                     │
│   ├─ ProjectVersion  (branch snapshot)                       │
│   └─ ScanResult      (one CLI / Quick Import run)            │
│        └─ ScanComponent → Library → CVE / License            │
└──────────────────────────────────────────────────────────────┘
```

* A **Project** is the top-level unit — usually one repository.
* A **Scan** captures the full dependency tree at a point in time.
* A **Library** is a globally-shared record (name + version + ecosystem) so CVE data is enriched once and reused across all projects.
* **CVEs** are pulled from deps.dev and OSV (including CWE IDs when OSV provides them); license data from deps.dev. In air-gapped mode the same data comes from an imported vulnerability snapshot instead.

---

## Key Workflows

1. **Register a project** → Quick Import (VCS) or CLI push
2. **Run a scan** → automatic on import, or via `POST /api/scan`
3. **Review** → Security Center for CVEs, License tab for policy violations
4. **Track over time** → Risk Trend chart with AI summaries
5. **Manage** → Update CVE status, adjust license policy, export reports

---

## Getting Help

* **Swagger UI** (local profile only): `http://localhost:8080/swagger-ui.html`
* **H2 Console** (local profile only): `http://localhost:8080/h2-console`
* **Issues**: [GitHub Issues](https://github.com/SalkCoding/Oswl/issues)

For production, API docs and the H2 console are disabled; use this documentation and [API Reference](API-Reference.md).
