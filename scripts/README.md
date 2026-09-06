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

## Verification drafts

[`verification/verify-multi-instance.sh`](verification/verify-multi-instance.sh) is an incomplete local multi-instance harness. Its default execution block is intentional, and a successful run is not evidence of production session/scheduler correctness. It requires a built JAR and local tools and uses unique temporary data under `build/cluster-verification.*`. It is not part of CI. Its identity and per-observed-cycle predicates have independent regression checks: `python -m unittest discover -s scripts/verification -p test_cluster_assertions.py`. Predicate success does not establish PostgreSQL/LB or process-failover behavior.

GitHub publishing helpers remain under [`.github/scripts/`](../.github/scripts/), beside the workflows that invoke them.
