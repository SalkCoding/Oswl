# OsWL — Project Guidelines

OsWL is an in-house SCA (Software Composition Analysis) platform for tracking and managing CVE security vulnerabilities and license risks in OSS components.

---

## Test Account
- Email: test@test.com  |  Password: 1q2w3e4r  |  Name: test
- OTP: `000000` (bypass in dev)  |  Test issuance OTP: `123456`

---

## Tech Stack
- **Backend:** Spring Boot 4.1.0 (Java 25) — WebMVC, Data JPA, Security, Mail, Validation, AspectJ
- **View:** Thymeleaf + thymeleaf-extras-springsecurity6
- **Frontend:** Vanilla HTML/CSS/JS · Tailwind CSS 3.4 (precompiled standalone CLI, **NO CDN**) · Chart.js · HTMX · Alpine.js
- **DB:** PostgreSQL (prod) / H2 file-mode (local profile, PostgreSQL-compatible)
- **Auth:** Spring Security + Email OTP (2FA) + Trusted Device + Single-Session Enforcement
- **API Docs:** springdoc-openapi — OpenAPI annotations live in `controller/spec/*Spec.java` interfaces only
- **Local SMTP:** GreenMail embedded (port 3025) — OTP code printed as `*** OTP CODE: NNNNNN ***` in server logs
- **Build:** Gradle Wrapper (`./gradlew`)

---

## Project Structure (Key Packages)

    src/main/java/com/salkcoding/oswl/
    ├── aop/             # Cross-cutting concerns (audit logging)
    ├── auth/            # Auth module — config, controller, dto, entity, enums, repo, security, service, web
    ├── client/          # External API clients (OSV, deps.dev, GitHub…)
    ├── config/          # App-wide config beans (air-gapped client, startup warnings)
    ├── controller/      # Business controllers, grouped by feature — same group names as domain/entity/
    │   │                #   below, plus ingest/ reporting/ scan/ scim/ vcs/ (no matching entity) and
    │   │                #   audit/ ai/ (controller-only). HomeController/LocalDevController/
    │   │                #   SettingsController have no single feature owner — stay flat.
    │   └── spec/        # Controller spec interfaces (all springdoc annotations here) — organized by
    │                    #   kind, not feature; this is a deliberately different axis, not an omission
    ├── domain/entity/   # JPA entities, grouped by feature: ai/ apikey/ jira/ license/ notification/
    │                    #   org/ policy/ project/ scan/ snapshot/ vulnerability/
    ├── domain/enums/
    ├── repository/      # Spring Data interfaces — same feature groups as domain/entity/ above
    ├── service/         # Grouped by feature: ai/ apikey/ container/ cvss/ gate/ git/ ingest/ jira/
    │                    #   license/ manifest/ notification/ org/ policy/ project/ reachability/
    │                    #   reporting/ scan/ scim/ snapshot/ vcs/ vulnerability/. SessionCipherService
    │                    #   has no single feature owner — stays flat.
    ├── dto/  exception/  license/  logging/  security/  util/  vdb/
    ├── scheduler/       # @Scheduled jobs + SchedulerLockConfig (ShedLock, opt-in cluster lock)
    ├── web/
    │   ├── config/      # MVC config
    │   ├── filter/      # Servlet filters (e.g. request/user MDC logging correlation)
    │   └── interceptor/
    resources/
    ├── application.yaml / application-local.yaml / application-prod.yaml
    ├── logback-spring.xml   # Console always; prod adds a rotating file (plain or JSON)
    ├── db/migration/        # Flyway (opt-in) — db/*.sql are the matching ddl-auto reference scripts
    ├── static/{css,js,img,icon,graphic,scripts}/
    └── templates/{auth, component-detail, error, fragments, license, mail, org-dashboard,
                   oss-notices, projects, reports, risk-trend, scan-history, security-center,
                   settings, version-diff}/

---

## Architecture & Code Style

**Backend**
- **Layered:** Controller → Service → Repository. Keep controllers thin.
- **Controller Spec:** All OpenAPI annotations go in `controller/spec/*ControllerSpec.java` interfaces. Implementations contain business logic only.
- **DI:** Lombok `@RequiredArgsConstructor` constructor injection only. `@Autowired` is forbidden.
- **Authorization:** Method-level `@PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")`.
- **Audit Logging:** Use `AuditLogService.log…()` for user actions and system events.
- **Profile Gating:** Local-only beans use `@Profile("local")` (e.g. `TestDataController`, `LocalSmtpConfig`).
- **JPA Cascade:** Project → ProjectVersion / ScanResult / ScanComponent / DependencyPath; Library → Cve (cascade ALL).

**Frontend**
- **Vanilla first.** Add HTMX (partial updates) or Alpine.js (local state) only when needed.
- **Tailwind CSS:** `/css/tailwind.css` (Gradle build output) only — CDN is forbidden.
- **HTMX pattern:** Return fragment-only response when `HX-Request: true` header is present.
- **Assets:** JS in `static/js/`, organized by page subdirectory.

---

## DB & Environments
- **Local DB:** H2 file (`./oswl-db.mv.db`). Console: `http://localhost:8080/h2-console` (JDBC `jdbc:h2:file:./oswl-db`, user `sa`).
- **DB Reset:** Stop server → delete `oswl-db.*` files → restart → lands on Setup screen.
- **Test Data Seed:** `GET /data/test` (auth required, local only) — wipes and re-seeds all data.
- **Encryption Key (`OSWL_ENCRYPTION_KEY`):** Dummy key hardcoded for local; production requires 32-byte key via `openssl rand -base64 32`.

---

## Build & Run

    ./gradlew bootRun          # Local run (profile=local, port 8080)
    ./gradlew build            # Full build (includes Tailwind)
    ./gradlew test             # JUnit tests
    ./gradlew buildTailwindCss # Tailwind rebuild only

---

## Conventions
- **DB naming:** snake_case tables/columns; entity PK is `Long id`.
- **DTOs:** `record` or Lombok `@Value` in `dto/` package.
- **Dependencies:** Add to `build.gradle` (backend) or use CDN/webjars (frontend).
- **Error pages:** `error/{401,403,404,500,503}.html` — use `_owl-error.html` fragment (shared owl illustration).
- **Log tone:** Business events → INFO/WARN/ERROR; details → `log.debug`.
- **Commit messages:** Conventional-commit type prefix only — `feat:`, `fix:`, `docs:`, `chore:`, etc. **No parenthetical scope** (`fix(security):`, `docs(readme):`). Put the scope in the description text itself instead (e.g. `fix: harden PolicyService YAML import and GitOps clone`, not `fix(security): harden PolicyService...`).
- **No internal tracking codes in comments/docs:** Never leave internal planning labels — roadmap/sprint/ticket-style codes like `S1`, `B7`, `A2`, `roadmap #13`, `H2/H3`, `E5.2`, `week 7` — in code comments, commit-adjacent doc prose, or `docs/*.md`. They mean nothing to a reader without the planning doc that produced them, and that doc isn't part of the shipped project. Explain the *reason* for the code in plain language instead. Real version numbers (e.g. `v1.0.4`, "added in v1.0.4") are fine to keep — they're meaningful on their own once released. (Internal planning docs like a working `ROADMAP.md` are themselves exempt — this rule is about what leaks out of them into permanent files.)

## Test Code Policy

- Do NOT modify, create, or delete any test files (e.g., *Test.java, *.test.ts, *.spec.ts) unless explicitly asked to.
- When fixing bugs or refactoring production code, leave all existing test files exactly as they are.
- If a test needs to be updated as a direct result of a change, ask first before touching it.
- Never auto-generate test stubs or test scaffolding without being explicitly instructed to do so.
