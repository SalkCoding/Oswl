# Custom secret and IaC rules

System administrators can read and publish the instance-wide rule set using `GET` and `PUT /api/settings/scan-rules`. Use the interactive API documentation or an authenticated, CSRF-protected request. Publication validates the whole set and uses its current `revision`; stale revisions receive HTTP 409. The initial empty set has revision -1. Publishing an empty list disables custom rules. Built-in rules remain active.

Example rule set:

```json
{"revision":-1,"rules":[{"id":"company-token","type":"SECRET","severity":"HIGH","description":"Company token detected","regex":"COMPANY_[A-Z0-9]{24}","fileSuffix":".env"}]}
```

`type` is `SECRET` or `IAC`. A suffix restricts matching to appropriate file names, for example `.tf`, `.yaml`, `Dockerfile` or `.env`. Rules inspect individual lines; IaC matches are text heuristics, not semantic infrastructure analysis. Descriptions are administrator-authored and must not contain secret values.

Publication creates a new optimistic revision used by subsequent scans, with an audit event containing revision and rule count. Existing findings retain `custom-<id>@<revision>` and their original description. Save the previous GET response if you need rollback: republish its rule list with the current revision. Each scan loads one coherent set from the database, so nodes do not depend on local cache invalidation.

Limits: 32 rules, 256 regex characters, 2,048 compiled instructions, 400 description characters, 1 MB per file, 16 MB total input, 2,000 visited files, 8,192 characters per line, five seconds and 300 findings. Unsupported lookarounds/backreferences and empty matches are rejected. [RE2/J](https://github.com/google/re2j) provides linear-time matching; the pinned engine version is 1.8. Symlinks are not followed. A limit or unreadable selected input creates an explicit high-severity `custom-scan-incomplete@<revision>` IaC finding. Rule loading/compilation failure uses `custom-scan-incomplete` when the revision is unavailable. The gate treats these markers and built-in scanner failure markers as incomplete coverage regardless of only-new or secret-policy filters. Ordinary IaC findings are displayed for review; there is no separate IaC gate threshold. Such a scan must not be interpreted as clean coverage.

Custom results contain location, rule id/version, severity and description. They contain neither matched text nor a secret fingerprint. Built-in scanning has its own existing fingerprint policy.

Migration `V33__custom_scan_rules.sql` adds a small configuration table; it does not change source or finding retention. Local tests cover no-secret output, versioned results, invalid/expensive patterns, input limits and direct service authorization. PostgreSQL migration/runtime verification remains environment-dependent.
