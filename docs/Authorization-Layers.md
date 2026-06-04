# Authorization layers

**Who this is for:** Developers, security reviewers, license/compliance leads, IT operations, and leadership — anyone who needs to understand *who can do what* in OsWL without reading source code.

---

## The short answer

OsWL combines **three separate ideas**. They use similar words in everyday language (“admin”, “role”, “member”) but mean different things in the product:

| Idea | Plain-language name | What it answers |
|------|---------------------|-----------------|
| **A** | **Role template** (instance-wide) | “Can this person use features like scan submit, license export, or settings?” |
| **B** | **Project membership** | “Can this person open *this* project’s data?” |
| **C** | **System administrator** | “Can this person manage the whole OsWL instance (users, audit log, all projects)?” |

A normal user usually needs **both A and B** to work on a project:

- **A** — e.g. `LICENSE_VIEW` to open the License page  
- **B** — a row in `project_members` for that project  

**System administrators (C)** can access all projects for support and governance, regardless of membership.

> **Naming tip for developers:** In code and docs, say **role template**, not “role” alone. Say **project membership**, not “project admin” (unless you mean the project-level `ADMIN` membership type).

---

## Layer A — Role templates (Admin / Developer / Viewer)

- Configured under **Settings → Admin → Role Templates**.
- Assigned to users when they are invited or edited.
- Implemented as a set of **permissions** (e.g. `SCAN_SUBMIT`, `SECURITY_CENTER_VIEW`).

### Built-in templates (first empty database)

On first startup, OsWL creates three templates you can adjust later:

| Template | Typical use | Summary |
|----------|-------------|---------|
| **Admin** | Instance owners / platform admins | All permissions in the catalog |
| **Developer** | Engineers who scan and triage | Projects, scans, security center (including status updates and export), license view/export, VCS and CLI keys |
| **Viewer** | Read-only stakeholders | View projects, scans, security center, license pages and exports — no scan submit, no settings |

These names describe **capabilities across the whole OsWL instance**. They are **not** the same as “member of project #42”.

---

## Layer B — Project membership

- Stored in the **`project_members`** table (one row per user per project).
- Answers: “Is this user allowed to see or change data for **project ID X**?”
- Enforced by **`ProjectAccessService`** on project-scoped pages and APIs (Security Center, scan history, license export, API keys, etc.).

### Project membership roles (technical)

Each membership row has a project-scoped role:

| Value | Meaning today |
|-------|-------------|
| **ADMIN** | Assigned to the project creator when the project is created |
| **MEMBER** | Default for other members when added |

Today, **feature access still follows Layer A (permissions)**. Project membership mainly prevents **cross-project access** (IDOR). Finer “project owner vs guest” rules may be added later using the ADMIN/MEMBER field.

### Who gets membership automatically?

- The user who **creates** a project is added as project **ADMIN**.
- On startup, a one-time bootstrap adds creators to projects that had no members yet.

**Collaboration note:** To let a second user access an existing project, they must be in `project_members` (today this is automatic for creators; broader team assignment may require operational process or future UI).

---

## Layer C — System administrator

- Flag on the user account (`isSystemAdmin`), created via the **setup wizard**.
- Can open **Settings → Admin** (users, role templates, audit log).
- **Bypasses project membership** — can open any project ID for support.
- Still uses the same login, 2FA, and session security as other users.

Do not confuse with:

- Role template named **“Admin”** → permission bundle only  
- Project membership **ADMIN** → creator on one project only  

---

## How the layers work together (examples)

| Person | Role template | Project member? | Can open project #5 Security Center? |
|--------|---------------|-----------------|--------------------------------------|
| System admin | any | optional | Yes (C bypasses membership) |
| Developer | Developer | Yes, project #5 | Yes (A + B) |
| Developer | Developer | No, not on #5 | No — blocked by B even with scan permissions |
| Viewer | Viewer | Yes, project #5 | Yes, read-only features per Viewer template |
| Viewer | Viewer | Yes, but no `SCAN_SUBMIT` | Cannot submit CLI scans (A), can still view if B ok |

---

## CLI scans (extra checks)

`POST /api/scan` is designed for automation, not browsers. It requires:

1. Valid **project API key**  
2. Submitter **email + password** with **`SCAN_SUBMIT`** (Layer A)  
3. Submitter listed in **`project_members`** for that project (Layer B)  

Failed attempts are recorded in the **audit log**. See [CLI Integration](CLI-Integration.md) and [Scan API security](Scan-Api-Security.md) (high-level only).

---

## Production and governance

- OsWL is built for **defense in depth**: session login, optional 2FA, permission checks, project membership, API keys, rate limiting, audit logging, and hardened production defaults.
- Operational details (environment variables, reverse proxy, health checks) are in [Production deployment checklist](Production-Deployment-Checklist.md).
- Technical enforcement points and endpoint list: [Project access control](Project-Access-Control.md).

---

## Related documentation

| Document | Content |
|----------|---------|
| [Administration](Administration.md) | Users, role templates, permissions list, audit log |
| [Project access control](Project-Access-Control.md) | Technical ACL reference |
| [Glossary](Glossary.md) | Definitions of terms |
| [Getting Started](Getting-Started.md) | Install and first login |
