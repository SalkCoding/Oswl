# Getting Started

This guide walks you through installing OsWL, running the setup wizard, and completing your first project scan.

---

## System Requirements

| Component | Requirement |
|---|---|
| **JDK** | 25 |
| **Build tool** | Gradle Wrapper (bundled — `./gradlew`) |
| **Database** | H2 file-mode (local / dev) or PostgreSQL 15+ (production) |
| **OS** | Linux, macOS, or Windows |
| **Memory** | Depends on scan workload; embedded AI needs additional RAM |

> No Node.js or npm is required — the Tailwind CSS standalone binary is downloaded automatically by Gradle on the first build.

---

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. Choose a Profile

OsWL ships with two Spring profiles:

| Profile | Database | Use case |
|---|---|---|
| `local` *(default)* | H2 file (`./oswl-db.mv.db`) | Development and evaluation |
| `prod` | PostgreSQL | Production deployment |

### 3. Start the Application

For local development, run `./gradlew bootRun` (PowerShell: `.\gradlew.bat bootRun`). The default `local` profile uses H2 and a fixed development encryption key. Use a separate, persistent key outside development; do not reuse the development key in production.

For production, build the deployable artifact with `./gradlew bootJar verifyProdJar` and run the resulting JAR with the `prod` profile. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and a persistent `OSWL_ENCRYPTION_KEY` that decodes to 32 bytes. Generate the key once with `openssl rand -base64 32` and retain it across restarts and restores. Prepare the PostgreSQL schema before startup: `prod` validates the schema and does not create it automatically.

See the [production checklist](Production-Deployment-Checklist.md) and [Docker commands](../../deploy/README.md) for deployment. A `.env` file alone is not loaded by `bootRun` or `java -jar`; export variables in the launching shell, supply Spring configuration, or use Compose with `--env-file`.

The default application port is **8080**. Memory requirements depend on the workload; local AI needs additional memory for the model and context window.

## Embedded AI Model (first run)

The current default is **Qwen3.5-2B Q4_K_M** (1,280,835,840 bytes, about 1.28 GB), downloaded from a pinned revision of `unsloth/Qwen3.5-2B-GGUF` on Hugging Face. The file is stored under `embedded-ai/model/Qwen/` and verified against the configured SHA-256. The llama.cpp runtime is installed separately under `embedded-ai/llama/` or on `PATH`.

When no model is available, `OSWL_EMBEDDED_AUTO_DOWNLOAD=true` permits background prefetch on startup. Prefetch does not start the sidecar or activate an AI provider. Start it from **Settings → AI** after installing the runtime. Set the variable to `false` to disable startup prefetch. Air-gapped mode disables automatic model downloads. See [Embedded AI](Embedded-AI.md) for model discovery, configuration and troubleshooting.

## Air-Gapped (Offline) Startup

Offline mode switches the supported vulnerability feeds to snapshot data; it is not a network firewall. Configure VCS, SMTP, webhooks and AI endpoints for your network separately.

For hosts without outbound internet access:

1. Set `OSWL_AIRGAPPED_ENABLED=true` before startup. Vulnerability / threat-intel lookups (OSV, deps.dev, EPSS, KEV) are then served from an imported offline snapshot — no outbound HTTP is attempted, and the embedded-model auto-download is skipped.
2. On an internet-connected machine, build a snapshot bundle:

   ```bash
   scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --out bundle.zip
   ```

   (Windows: `scripts/oswl-vdb/oswl-vdb.ps1`.) To target the bundle at your actual dependencies, first export a wanted-list from a connected OsWL instance: `GET /api/admin/snapshot/wanted-list`.
3. Transfer `bundle.zip` to the air-gapped host and import it as a System Admin via `POST /api/admin/snapshot/import` (multipart upload), or whitelist a directory with `OSWL_AIRGAPPED_IMPORT_DIR` and use `POST /api/admin/snapshot/import-from-path`. See [Administration — Offline snapshot bundles](Administration.md).
4. For embedded AI, place a `.gguf` model you obtained yourself into `embedded-ai/` before clicking **Start**.

---

## Setup Wizard

On the very first startup (empty database), OsWL redirects application-page requests to `http://localhost:8080/setup`.

The wizard collects:

| Field | Description |
|---|---|
| **Admin Email** | Used as the login credential for the System Admin account |
| **Password** | Must meet the minimum length policy (default: 8 characters) |
| **Display Name** | Shown in the UI and audit log |

After you submit, OsWL creates the admin account and redirects to the login page.

> If you need to restart from a clean state in local mode, stop the server and delete `oswl-db.mv.db` (and `oswl-db.trace.db` if present), then restart.

---

## First Login

1. Open `http://localhost:8080/login` and enter the account created in Setup.
2. If email two-factor authentication is required, enter the six-digit code sent for the current session. The code expires after three minutes, and resend has a 60-second cooldown.
3. With the default local GreenMail SMTP configuration, received codes are displayed in the server log as `*** OTP CODE: ... ***`. If external SMTP is configured, check the recipient mailbox instead. There is no fixed `000000` bypass.
4. Accounts marked for a password change must set a new password before continuing.

## Seeding Test Data (local only)

After completing Setup, `GET /data/test` deletes existing project, scan and library data and queues real Quick Import jobs for the public repositories in `DemoImportCatalog`. It redirects to Projects; imports run asynchronously and need network access. It does not create a fixed test account or a predetermined set of vulnerabilities.

These development endpoints exist in the local source set under `local`/`test` profiles and are excluded from the production JAR. Under `local`, `/data/**` is permitted without authentication, so use this destructive reset endpoint only on an isolated development instance.

`GET /data/test-api-key` issues a project-scoped key for the first available project. It returns 404 when no project exists; wait for the demo imports to create one.

## UI/Accessibility Test Harness (developer-only)

`./gradlew uiTest` runs the existing Playwright browser suite against a real application instance. The accessibility tests run axe-core on their selected pages; other tests cover interactions and request flows. The task downloads Chromium when needed and is separate from `test`/`check`.

JUnit HTML reports are in `build/reports/tests/uiTest/`; axe reports are in `build/reports/axe/`. For offline execution, provision the browser binaries and build dependencies in advance and set `PLAYWRIGHT_BROWSERS_PATH` where required. Browser installation does not replace dependency provisioning.

## Access control (recommended reading)

* [Authorization layers](Authorization-Layers.md) — role templates (Admin / Developer / Viewer) vs project membership
* [Production deployment checklist](Production-Deployment-Checklist.md) — before going live with `prod`

## Next Steps

* [Connect your first VCS repository](Quick-Import.md)
* [Submit a scan via the CLI](CLI-Integration.md)
* [Explore the Security Center](Security-Center.md)
