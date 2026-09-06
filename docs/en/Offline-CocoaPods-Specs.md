# Scoped offline CocoaPods Specs

Air-gapped scans can resolve CocoaPods names to source repositories and license metadata from an imported `cocoapods-specs.jsonl` source. The existing Podfile.lock parser and vulnerability enrichment path consume this mapping. Missing or unsupported Specs remain unanalysed; importing Specs alone does not supply vulnerability data.

Supply original podspec JSON files that your organization may use and a JSON manifest containing `{ "file": "FixturePod.podspec.json", "origin": "https://example.org/source/FixturePod.podspec.json" }` entries. Build a bundle with:

```powershell
python scripts/vdb/build-cocoapods-specs.py manifest.json scoped-specs.zip
```

Import the ZIP through the existing offline snapshot settings. Use MERGE when adding mappings to an existing collection; REPLACE replaces the Specs source carried by the bundle. Other sources absent from the ZIP are unaffected. Import the corresponding OSV SwiftURL vulnerability records separately, using the repository name such as `github.com/Owner/Repo` and exact version. Explicit empty vulnerability records represent completed clean lookups; absent records remain unresolved.

The bundle requires format version 2 and the normal SHA-256 file manifest. Each record contains name, version, HTTPS origin without credentials/query/fragment, SHA-256 of the original UTF-8 podspec, and the original podspec text. Identity must agree with its contents. Limits are 1,000 stored Specs and 64 KiB per original spec. Invalid checksums, identities, limits or legacy bundles are rejected transactionally. Export preserves original metadata and source dates rather than assigning fresh upstream dates.

The builder never fetches or redistributes the upstream Specs index. Included regression fixtures are authored for this repository. A pod's declared license describes that pod and is not evidence of permission to redistribute the entire Specs index; operators supply metadata they are entitled to transfer. No third-party Specs dataset is shipped by this feature.

Verification: scoped import/export and provenance preservation, tampering/legacy rejection, missing mapping, preserved unresolved/CVSS evidence, and Podfile → real ingest → offline enrichment of an owned vulnerable fixture. This does not certify current upstream advisory coverage.
