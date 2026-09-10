# What's new in OsWL 1.0.5.1

OsWL 1.0.5.1 improves dependency metadata, scan reliability and the light interface.

## Using this release

- Login, search and settings use light mode. Settings show unsaved changes separately from errors. AI parameter errors appear beside the field, and a successful save remains visible beside Save.
- AI response diversity accepts 0–2 (default 0.15); maximum tokens accepts integers 256–8192 (default 1200). Empty values use defaults. Provider and analysis-task limits can override their use; Anthropic does not use the temperature setting.
- Deleted projects appear in Trash and can be restored while retained. CLI API keys can be deleted from Settings; deleted keys stop working.
- CVE identifiers have a direct NVD link. Fixed versions are selected from supported numeric release intervals. Maven parent properties and supported Gradle version constraints improve dependency resolution.
- Diagnostics distinguish failures, warnings and skipped checks and format disk capacity. Empty archive exports explain why there is no file; eligible exports download JSON.
- Cache “refresh on every scan” applies when a scan requests data, not as continuous background refresh.

## Upgrade

Use Java 25 and back up the database and persistent files first. Read the [deployment checklist](Production-Deployment-Checklist.md) and [backup instructions](Backup-And-Restore.md). On databases using migrations, apply the migrations through V35. Do not assume a JAR replacement has migrated your database.

Download `oswl-1.0.5.1.jar` and `SHA256SUMS` from the matching GitHub Release. Follow [Getting started](Getting-Started.md) for configuration. Browser security alerts require web push/VAPID configuration and browser permission; external AI, SMTP and VCS integrations require their own configuration.

## Accuracy and scope

Existing saved scans are not rewritten by upgrading. Rescan to apply parser and enrichment fixes. Manifest declarations are not always the dependencies selected by a build; unresolved versions require investigation. Missing license or fix information is incomplete evidence, not a clean bill of health or proof that no patch exists. Complex version ranges, ecosystem-specific ordering and online/offline parity are not fully resolved in this release.

See [Analysis coverage](Analysis-Coverage.md) for supported evidence and limitations.
