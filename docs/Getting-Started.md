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

> ⚠️ **`OSWL_ENCRYPTION_KEY`** — a 32-byte Base64 key used to encrypt stored VCS access tokens. In `local` mode a dummy key is used automatically. In `prod` you **must** set this to a stable, secret value; losing it makes previously stored VCS credentials unrecoverable.

The application starts on port **8080** by default.

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

## Next Steps

* [Connect your first VCS repository](Quick-Import.md)
* [Submit a scan via the CLI](CLI-Integration.md)
* [Explore the Security Center](Security-Center.md)
