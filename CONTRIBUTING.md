# Contributing to OsWL



Thank you for your interest in OsWL. This document covers the basics for code and documentation contributions.



## Before you start



- Search [existing issues](https://github.com/SalkCoding/Oswl/issues) to avoid duplicate work.

- For larger changes, open an issue first to align on scope and approach.

- Read [`docs/Getting-Started.md`](docs/Getting-Started.md) for local setup and [`docs/API-Reference.md`](docs/API-Reference.md) for endpoint conventions.



## Development setup



| Requirement | Version |
|---|---|
| JDK | 25+ |
| PostgreSQL | 18 (see `docker-compose.yml`) |
| Gradle | wrapper included (`./gradlew`) |



```bash

git clone https://github.com/SalkCoding/Oswl.git

cd Oswl

cp .env.example .env   # if present; set POSTGRES_PASSWORD

docker compose up -d db

./gradlew bootRun

```



The `local` profile is the default (`application.yaml`). You do not need `--spring.profiles.active=local` unless overriding another profile.



### Repository layout



| Module | Purpose |
|---|---|
| `oswl-app` | Spring Boot app — controllers, JPA, templates, `bootJar` |
| `oswl-scan-core` | Manifest parser, BOM resolver, `ScanPayload` |
| `oswl-domain` | Shared domain enums |
| `oswl-vuln-client` | OSV, deps.dev, EPSS, KEV HTTP clients |



Application sources live under `oswl-app/src/main/java/`. Local-only dev controllers are in `oswl-app/src/local/java/` (excluded from `bootJar`).



## Code style



- Match existing patterns in the package you are editing.

- Controllers stay thin; business logic belongs in services.

- OpenAPI annotations live in `controller/spec/*ControllerSpec.java` only.

- Use `@RequiredArgsConstructor` constructor injection (no field `@Autowired`).

- Project-scoped routes must call `ProjectAccessService` where applicable.

- User-facing strings go in `messages.properties` and `messages_ko.properties`.



## Tests



```bash

./gradlew test                    # all modules; excludes @Tag(live)

./gradlew testFast                # fast-tagged unit tests (CI PR gate)

./gradlew testParser              # parser / BOM verification

./gradlew testIntegration         # @SpringBootTest, repositories

./gradlew testAuth                # auth / security tests

./gradlew testWeb                 # controller tests

./gradlew jacocoTestReport        # coverage report

./gradlew verifyProdJar           # prod JAR must not contain TestDataController

```



JaCoCo HTML report: `oswl-app/build/reports/jacoco/test/html/index.html`



Add or update tests when you change security-sensitive or non-trivial service logic. Use JUnit `@Tag` from `com.salkcoding.oswl.testing.TestTags` (or run `python tools/tag-tests.py` after adding new test classes).



## Pull requests



1. Branch from `develop` (or `main` for hotfixes — mention it in the PR).

2. Keep commits focused; describe **why** in the PR body.

3. Ensure `./gradlew test` passes locally.

4. Update `docs/` (and `docs/ko/` when user-facing) if behavior or APIs change.



## Documentation & i18n



- English docs: `docs/`

- Korean docs: `docs/ko/`

- GitHub Wiki is auto-synced from **`docs/` only** on push to `main` (`.github/workflows/wiki-sync.yml`). Korean docs stay in the repository under `docs/ko/`.



## Security



Do not open public issues for vulnerabilities. Contact the maintainers privately via GitHub.



## License



By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).


