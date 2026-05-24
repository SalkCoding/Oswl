<div align="center">

# 🦉 OsWL

**Open-source Software Watchlist — SCA Platform**

Track CVE vulnerabilities and license risks across all your software components.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-supported-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)

**English** | [한국어](README.ko.md)

</div>

---

## What is OsWL?

**OsWL** (Open-source Software Watchlist) is an in-house **SCA (Software Composition Analysis)** platform that tracks and manages security vulnerabilities (CVEs) and license risks in OSS dependencies.

It provides a single dashboard for your entire software portfolio — connect your Git repositories for automatic import, or push scan results via the CLI, then immediately see CVSS-ranked vulnerability lists, license compliance status, risk trends over time, and AI-generated insights.

### Key Features

| Feature | Description |
|---|---|
| **Security Center** | Full CVE list with CVSS scores, severity ranking, and status management (Open / Suppressed / False Positive) |
| **License Analysis** | SPDX license detection per dependency with policy enforcement (Permitted / Caution / Restricted) |
| **Risk Trend** | Historical risk charts across up to 10 scans showing CVE count and license posture changes |
| **Version Diff** | Side-by-side comparison of two scan results — added, removed, and changed dependencies |
| **Quick Import** | One-click import from GitHub / GitLab / Bitbucket via VCS connection |
| **CLI Integration** | Language-agnostic scan submission via REST API with project-scoped API keys |
| **AI Insights** | Optional LLM-generated risk summaries for CVE posture and license compliance |
| **Role-Based Access** | Fine-grained permission system with admin-managed role templates |
| **Audit Logging** | Immutable audit log for all user and system events, with CSV export |
| **2FA / Trusted Devices** | Email OTP two-factor authentication with per-browser trusted-device support |

---

## Quick Start

### Prerequisites

| Tool | Version |
|---|---|
| JDK | 25+ |
| Gradle Wrapper | included (`./gradlew`) |
| PostgreSQL | 15+ (production) |
| (Optional) Docker | for running PostgreSQL locally |

### 1. Clone

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. Run locally (H2 file-mode)

```bash
./gradlew bootRun
# Application starts on http://localhost:8080
```

The `local` profile is active by default. It uses an embedded H2 database (`./oswl-db.mv.db`) — no external database required.

On first run the **Setup Wizard** opens automatically at `http://localhost:8080/setup`.  
Complete it to create the first System Admin account.

### 3. Run with PostgreSQL (production profile)

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=your_password
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

---

## Building

```bash
# Full build (compiles Java + Tailwind CSS)
./gradlew build

# Rebuild Tailwind CSS only
./gradlew buildTailwindCss

# Run tests
./gradlew test

# Test coverage report → build/reports/jacoco/test/html/index.html
./gradlew jacocoTestReport
```

> **Note:** The first build downloads the Tailwind CSS standalone CLI binary (~7 MB) to `build/tools/`. Subsequent builds use the cached binary.

---

## Configuration Reference

All settings are controlled via environment variables or `application.yaml` profiles.

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Active profile: `local` or `prod` |
| `OSWL_ENCRYPTION_KEY` | *(dummy in local)* | Base64-encoded 32-byte AES key for VCS token encryption. **Required in production.** Generate with `openssl rand -base64 32` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/oswl` | PostgreSQL JDBC URL (prod profile) |
| `DB_USERNAME` | `oswl` | Database user (prod profile) |
| `DB_PASSWORD` | `oswl` | Database password (prod profile) |
| `OSWL_CLONE_TEMP_DIR` | system temp | Directory for temporary git clones during Quick Import |
| `OSWL_GITHUB_API_BASE` | `https://api.github.com` | GitHub API base URL (override for GHES) |
| `OSWL_RISK_TREND_LIMIT` | `10` | Maximum scans shown in the risk trend chart |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | Maximum records per page in audit log API |
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | Months before audit log records are auto-deleted |

---

## Local Development Extras

### H2 Console

```
URL:  http://localhost:8080/h2-console
JDBC: jdbc:h2:file:./oswl-db
User: sa
Pass: (empty)
```

### OTP Email (local profile)

The `local` profile starts an embedded **GreenMail** SMTP server. No real email is sent.  
OTP codes appear in the server log:

```
*** OTP CODE: 123456 ***
```

### Seed Test Data

After logging in, call:

```
GET http://localhost:8080/data/test
```

This resets **all** existing data and populates the database with a rich set of sample projects, scans, CVEs, and licenses.

---

## Architecture Overview

```
Browser / CLI
     │
     ▼
Spring MVC Controllers  (thin — delegates to Service)
     │
     ▼
Service Layer           (business logic, transactions)
     │
   ┌─┴──────────────────┐
   ▼                    ▼
JPA Repositories    External Clients
(PostgreSQL / H2)   (NVD · OSV · deps.dev · VCS APIs)
```

**Core domain model:**

```
Project
 └── ProjectVersion (per branch)
 └── ScanResult     (per CLI / Quick Import scan)
      └── ScanComponent
           └── DependencyPath

Library  (shared across projects — group:artifact@version)
 └── Cve
 └── LicensePolicyEntry
```

---

## API Documentation

Interactive Swagger UI is available at:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI spec (JSON): `http://localhost:8080/v3/api-docs`

---

## Documentation

Full documentation is available in the [`docs/`](docs/) folder:

| Page | Description |
|---|---|
| [Home](docs/Home.md) | Platform overview and navigation guide |
| [Getting Started](docs/Getting-Started.md) | Installation, setup wizard, first project |
| [User Guide](docs/User-Guide.md) | Day-to-day usage of the dashboard |
| [Quick Import](docs/Quick-Import.md) | Importing projects from GitHub / GitLab / Bitbucket |
| [CLI Integration](docs/CLI-Integration.md) | Submitting scans from build pipelines |
| [Security Center](docs/Security-Center.md) | Managing vulnerabilities (CVEs) |
| [License Analysis](docs/License-Analysis.md) | License compliance and policy management |
| [Risk Trend](docs/Risk-Trend.md) | Interpreting historical risk charts |
| [Version Diff](docs/Version-Diff.md) | Comparing two scan results |
| [Administration](docs/Administration.md) | Users, roles, audit logs, security settings |
| [API Reference](docs/API-Reference.md) | REST API endpoint summary |
| [Glossary](docs/Glossary.md) | Terms and definitions |

---

## License

This project is licensed under the [MIT License](LICENSE).
