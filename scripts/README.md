# Repository tools

Run commands from the repository root unless a tool says otherwise. Scripts are executable tools; Docker configuration lives in [deploy/](../deploy/README.md), and human-readable guides live in [docs/](../docs/README.md).

## Development

- [`dev/check-java.sh`](dev/check-java.sh) and [`dev/check-java.ps1`](dev/check-java.ps1): Java 25 preflight used by Gradle. Run with Bash or PowerShell.
- [`dev/oswl-bootRun.command`](dev/oswl-bootRun.command): macOS Finder launcher for local `bootRun`; requires a JDK and Bash.
- [`dev/measure-agent-context.py`](dev/measure-agent-context.py): Python/Git tool for comparing bounded source reads against a baseline. Example: `python scripts/dev/measure-agent-context.py --baseline 7695b20`.

## Operations

- [`oci/scan-image.py`](oci/scan-image.py): immutable OCI registry/layout inspection and optional OSV queries, without executing an image. See [container image inspection](../docs/en/Container-Image-Inspection.md).

- [`vdb/build-cocoapods-specs.py`](vdb/build-cocoapods-specs.py): builds a scoped offline Specs ZIP from operator-supplied podspec files without network access. See [offline CocoaPods Specs](../docs/en/Offline-CocoaPods-Specs.md).

- [`ops/verify-restore.sh`](ops/verify-restore.sh): interactive verification against a freshly restored instance. Requires Bash, curl, access to the instance and email OTP login. Follow the [restore guide](../docs/en/Backup-And-Restore.md); this is not an unattended health check.
- [`oswl-vdb/oswl-vdb.sh`](oswl-vdb/oswl-vdb.sh) and [`oswl-vdb/oswl-vdb.ps1`](oswl-vdb/oswl-vdb.ps1): wrappers for building, verifying and inspecting offline vulnerability bundles through Gradle. Their existing public paths are retained. See the [offline deployment guide](../docs/en/Production-Deployment-Checklist.md#71-air-gapped--offline-snapshot-v104).

## Local verification

- CLI retry identity: `python -m unittest discover -s scripts/cli -p test_retry_identity.py -v` requires Bash, jq, curl and PowerShell (`pwsh` by default; override executables with `OSWL_TEST_BASH` / `OSWL_TEST_POWERSHELL`). It extracts the generated CLI without installing it, replaces only configuration/manifest collection with fixtures, and exercises real upload commands against loopback HTTP. It checks random key separation, lost-response retransmission with identical JSON, malformed keys and terminal conflicts. It does not test manifest packaging or a deployed server. Verified on Git Bash with PowerShell 5.1 and 7; native macOS/Linux remain unverified.

  Local verification used jq 1.8.1 Windows AMD64 from the [official download page](https://jqlang.org/download/), SHA-256 `23cb60a1354eed6bcc8d9b9735e8c7b388cd1fdcb75726b93bc299ef22dd9334`, checked against the [official checksums](https://raw.githubusercontent.com/jqlang/jq/master/sig/v1.8.1/sha256sum.txt). The [versioned COPYING file](https://raw.githubusercontent.com/jqlang/jq/jq-1.8.1/COPYING) covers MIT and incorporated code notices (including ICU and BSD-style terms); preserve applicable notices with copies. The binary and COPYING were kept together under ignored `build/cli-verification/`, not added to OsWL distribution or offline bundles. This test does not introduce vulnerability data or expand redistribution permissions.

- PostgreSQL dependency evidence migration verification: provide an isolated PostgreSQL database
  in `OSWL_VERIFY_POSTGRES_URL` (JDBC URL), with optional `OSWL_VERIFY_POSTGRES_USER`
  (default `postgres`) and `OSWL_VERIFY_POSTGRES_PASSWORD`, then run
  `gradlew test --rerun --tests '*DependencyEvidenceMigrationTest'` and remove those variables.
  The account needs permission to create a schema. The opt-in test creates a uniquely named
  schema in a transaction, checks V37 twice and long Unicode evidence round trips, then rolls
  the transaction back and verifies schema removal. It is skipped without the URL.
  Use a disposable database; this checks the column migration, not full upgrade sequencing,
  production lock duration, or ingestion concurrency.

- OSV NuGet live client verification: set `OSWL_VERIFY_OSV_NUGET=true` in the current shell,
  run `gradlew test --tests '*OsvNugetLiveVerificationTest'`, then remove the variable.
  It sends read-only queries for System.Text.Json 7.0.0, 8.0.3 and 8.0.4 to the public OSV API.
  The test checks the CVE-2024-30105 boundary and keeps other advisories separate; it does not
  certify that any package version is free of vulnerabilities. It is skipped by default and
  provider changes or network failures can fail the explicit live run.


## Verification drafts

[`verification/verify-multi-instance.sh`](verification/verify-multi-instance.sh) is an incomplete local multi-instance harness. Its default execution block is intentional, and a successful run is not evidence of production session/scheduler correctness. It requires a built JAR and local tools and uses unique temporary data under `build/cluster-verification.*`. It is not part of CI. Its identity and per-observed-cycle predicates have independent regression checks: `python -m unittest discover -s scripts/verification -p test_cluster_assertions.py`. Predicate success does not establish PostgreSQL/LB or process-failover behavior.

GitHub publishing helpers remain under [`.github/scripts/`](../.github/scripts/), beside the workflows that invoke them.
