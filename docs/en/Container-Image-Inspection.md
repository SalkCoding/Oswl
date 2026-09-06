# Container image inspection

`python scripts/oci/scan-image.py` inspects an immutable OCI image without running it or requiring Docker. It verifies SHA-256 manifest, config and compressed layer contents, checks uncompressed `diff_id` values, and composes whiteouts before reading the final installed OS package database. It does not extract files into the host filesystem. Supported inventory databases are Alpine apk and Debian/Ubuntu dpkg; source package names and source versions are used for vulnerability matching. Language package inventories, RPM, Windows images and zstd layers are currently unsupported; missing/unsupported inventory fails explicitly.

From the repository root, with Python 3.11 or later:

```powershell
python scripts/oci/scan-image.py --image registry-1.docker.io/library/alpine@sha256:686d8c9dfa6f3ccfc8230bc3178d23f84eeaf7e457f36f271ab1acc53015037c --platform linux/amd64 --query-osv --output image-scan.json --report image-report.json
```

Outputs must be new paths. The scan JSON is the existing scan ingestion component payload; submit it using the deployment's authenticated scan workflow. The separate report records the selected digest, platform, installed package count and advisory query status. `--query-osv` queries exact package versions online and records advisory IDs and time. Without that flag, the report says `UNQUERIED`; package inventory alone does not mean a clean security result. Failed or truncated advisory responses fail the operation. Advisory IDs are point-in-time findings and can change as upstream records change.

Offline OCI layouts use `--layout <directory> --digest sha256:<manifest-or-index-digest>` instead of `--image`. Supply the immutable digest explicitly. Private registries can use `--credentials-file <private-json-file>` containing `username` and `password`. Restrict access to that file. Credentials are sent only to the same registry authentication realm (or Docker Hub's designated authentication host), never to redirected blob/CDN hosts. Transport exceptions suppress URLs and credentials. Custom cross-host authentication realms require a separate reviewed integration and are rejected.

Default budgets are 120 seconds, 512 MiB compressed content, 2 GiB expanded layer bytes, 256 layers, 250,000 entries per layer, 16 MiB per selected package DB and 50,000 packages. Unsupported compression, traversal, sparse selected entries, unresolved links, package DB removal and pending dpkg updates produce an incomplete-inspection error instead of an empty success. Temporary downloads are removed on normal success and failure; OS force termination can leave OS temporary files for the operator's normal temp retention policy.

The actual Alpine 3.16.0 linux/amd64 run selected manifest `sha256:4ff3ca91275773af45cb4b0834e12b7eb47d1c18f770a0b151381cd227f4c253`, verified one layer and collected ten installed source packages. OSV returned known advisories for BusyBox, musl, OpenSSL and zlib. See [the recorded result](../ko/ui-operations-evidence/oci-image-20260907.json). Tests also exercise whiteout order, removed databases, Debian source versions, traversal/budgets/hash mismatch, credential isolation and incomplete advisory responses.

The implementation follows the [OCI layer specification](https://github.com/opencontainers/image-spec/blob/main/layer.md) and [registry token authentication protocol](https://distribution.github.io/distribution/spec/auth/token/). Dockerfile analysis remains a separate heuristic path; it is not renamed as image inspection.

Additional actual registry checks on 2026-09-07 inspected Debian 12.0-slim (63 source packages, 27 with OSV advisory IDs) and Ubuntu 22.04 (70 source packages, 24 with advisory IDs). Immutable index and platform digests and per-package results are recorded in the [Debian evidence](../ko/ui-operations-evidence/oci-debian-20260907.json) and [Ubuntu evidence](../ko/ui-operations-evidence/oci-ubuntu-20260907.json). These checks exposed an overly broad rejection of Debian's unrelated `/lib` link; the inspector now rejects a non-directory ancestor only when the selected package database requires that path. Eight Python checks passed after the correction.
