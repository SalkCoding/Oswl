# Getting Started

This guide walks you through installing OsWL, running the setup wizard, and completing your first project scan.

---

## System Requirements

| Component | Requirement |
|---|---|
| **JDK** | 25 or later |
| **Build tool** | Gradle Wrapper (bundled — `./gradlew`) |
| **Database** | H2 file-mode (local / dev) or PostgreSQL 15+ (production) |
| **OS** | Linux, macOS, or Windows |
| **Memory** | 512 MB minimum, 1 GB+ recommended |

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

**Local (H2, zero config):**

```bash
./gradlew bootRun
```

**Production (PostgreSQL):**

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=changeme
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

> **`OSWL_ENCRYPTION_KEY`** — protects stored secrets such as VCS tokens. In `local`, a development key may be generated automatically. In **`prod`**, you **must** set a stable value before startup; the application will not start without it. Losing the key makes previously stored VCS credentials unusable.

The application starts on port **8080** by default.

---

## Embedded AI Model (first run)

OsWL can run its AI features fully on-premise through an embedded llama.cpp sidecar. On first boot, if no `.gguf` model exists in `embedded-ai/`, OsWL starts a background download of the default **Qwen3-1.7B** model (~1.2 GB):

* Downloaded from the upstream Hugging Face repository (`ggml-org/Qwen3-1.7B-GGUF`), with SHA-256 integrity verification; point `OSWL_EMBEDDED_DEFAULT_MODEL_URL` at a self-hosted mirror to avoid depending on a third-party host.
* Download-only — it never starts the sidecar or changes the active AI provider on its own. Progress appears in **Settings → AI**; click **Start** there once the file is ready.
* Opt out with `OSWL_EMBEDDED_AUTO_DOWNLOAD=false`. In air-gapped mode the download is never attempted (see below).

The `llama-server(.exe)` binary itself is the one manual step — download it from the [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases) and place it in `embedded-ai/` (or on `PATH`). See [Embedded AI](Embedded-AI.md) for details.

---

## Air-Gapped (Offline) Startup

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

On the very first startup (empty database), OsWL redirects every request to `http://localhost:8080/setup`.

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

1. Navigate to `http://localhost:8080/login`.
2. Enter the email and password you created in the setup wizard.
3. If **Two-Factor Authentication** is enabled (admin-configurable), you will be prompted for a 6-digit OTP sent to your email.
   * In `local` mode the OTP appears in the server log: `*** OTP CODE: NNNNNN ***`
   * Development shortcut: `000000` is accepted when using the test profile.
4. On first login with a temporary password, OsWL forces an immediate password change.

---

## Seeding Test Data (local only)

After logging in, call:

```
GET http://localhost:8080/data/test
```

This endpoint (available **only** in the `local` profile):

* Deletes all existing projects, scans, libraries, and CVEs.
* Re-populates the database with a rich realistic dataset: multiple projects across Maven and npm ecosystems, dozens of CVEs at various severities, mixed license statuses, and multiple historical scans for trend visualization.

A test API key is also available at:

```
GET http://localhost:8080/data/test-api-key
```

---

## UI/Accessibility Test Harness (developer-only)

`./gradlew uiTest` boots the real application on a random port and drives it with headless Chromium via Playwright, then runs an axe-core accessibility audit on each page it visits — catching things a curl-based smoke check structurally cannot see (Alpine.js actually initializing, a click firing the expected DOM update, real WCAG contrast/landmark issues on the rendered page). It is deliberately kept separate from `./gradlew test`/`check` since it downloads a browser (hundreds of MB, cached after the first run under `%LOCALAPPDATA%\ms-playwright` / `~/.cache/ms-playwright`) and boots a full Spring context; run it explicitly when working on templates, JS, or accessibility. Reports land in `build/reports/axe/`. In an offline/air-gapped build environment, pre-populate that browser cache and point the `PLAYWRIGHT_BROWSERS_PATH` environment variable at it instead of relying on the automatic download.

---

## Access control (recommended reading)

* [Authorization layers](Authorization-Layers.md) — role templates (Admin / Developer / Viewer) vs project membership
* [Production deployment checklist](Production-Deployment-Checklist.md) — before going live with `prod`

## Next Steps

* [Connect your first VCS repository](Quick-Import.md)
* [Submit a scan via the CLI](CLI-Integration.md)
* [Explore the Security Center](Security-Center.md)
