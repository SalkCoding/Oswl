# UI States Checklist — empty / loading / failure

Audit of every major screen's async regions (fetch calls, HTMX swaps, polling loops, lazy loads).
For each region three states are classified:

- **Loading** — is there a visible indicator while data is in flight?
- **Empty** — is there a distinct empty state (not just a blank area)?
- **Failure** — is there a failure state, and does it offer a next action (retry button, settings link, guidance text)?

Status values: **OK** (already covered), **Fixed** (gap closed in this pass), **Deferred** (known gap, not fixed — reason given).

## Projects

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| `projects/index.html` — project cards grid (server-rendered + JS refresh) | SSE auto-reload while scanning | **Fixed** — no empty state for the active grid (trash had one); added server-side state + `checkActiveEmpty()` in `projects.js` | Card-level scan states already covered (FAILED → "Re-import to retry", no-scan → guidance) | Guidance text on the card |
| `projects/index.html` — trash actions (restore / delete, single & bulk) | n/a (instant) | OK — trash empty state, incl. JS re-check | **Fixed** — 5 fetch chains in `projects.js` had no `.catch`; network failure was silent | Error toast (existing failure strings) |
| `projects/index.html` — background `refreshProjectCards/refreshTrashCards` | n/a | OK | OK-ish — silent `console.error` only; stale cards remain usable | Deferred — low impact (optimistic UI already removed the card; next user action re-fetches) |
| `projects/index.html` — New Project slide-out | OK — "Creating…" label | n/a | OK — inline error box | Re-submit |
| `projects/index.html` — scan-status SSE | OK | n/a | OK — fallback reload after 8 s, capped retries, login redirect on 401 | Automatic |
| `projects/quick-import.html` — per-provider repo browsers | OK — skeleton rows | OK — distinct "no repos" / "no search match" | OK — inline error text | Header **Refresh** button; error text points to Settings → VCS |
| `projects/quick-import.html` — import jobs (poll/SSE) | OK — progress log, phase labels, ETA | n/a | OK — per-entry error icon; every failure message carries guidance | Cancel / Import Another |
| `projects/quick-import.html` — SBOM upload | OK — busy label | n/a | OK — inline error | Re-submit |
| `projects/git-integration.html` — PAT connect / add account | OK — spinner + status text | n/a | OK — inline error + "how to get a token" steps | Re-submit |
| `projects/git-integration.html` — repo table | OK — reload spinner/status chip | **Fixed** — blank when account had no repos or search matched nothing; added empty state | **Fixed** — fetch failure was console-only with a blank table; added error row | **Retry** button (re-invokes `reloadRepos()`) |
| `projects/git-integration.html` — Import / Import All | OK — button state | n/a | **Fixed** — failures were silent and "✓ Imported!" showed regardless; now counts failures | Error text naming the failed count; Import button retries |
| `projects/cli-integration.html` | n/a — static instructions | n/a | n/a | — |

## Security Center

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| Component table | Server-rendered | OK — "No scan results yet" + guidance | n/a (server-rendered) | — |
| AI posture insight | OK — "Generating…" skeleton | OK — dashed "No AI insight was produced" card | OK — explicit FAILED/empty card | **Generate now** retry button; alert points to Settings → AI |
| Scan-in-progress banner | OK — spinner banner + 2.5 s poll, auto-reload on completion | n/a | OK — poll stops on FAILED | — |
| Component detail slide-over (HTMX `hx-get` → `#slideout-content`) | **Fixed** — panel opened instantly with stale/blank content; added spinner overlay | n/a | **Fixed** — 404/500/network was silent (no `htmx:responseError` handler existed anywhere); added error card | **Retry** button (re-clicks the originating row) |
| Bulk actions / batch PR | n/a | n/a | OK — `alert()` with failure message (existing convention) | Re-run the action |
| Saved views | n/a | OK — "No saved views yet." | OK — load failure leaves the empty list with the dropdown usable | Deferred — minor (console-only) |

## Component Detail (slide-over content)

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| CVE list | Server-rendered | OK — "No security vulnerabilities detected" | n/a | — |
| Dependency paths | Server-rendered | OK — explicit empty branch | n/a | — |
| Jira ticket creation | OK — busy flag | n/a | OK — `oswl-ai-toast` error toast | Re-click |
| AI plain summary refresh | OK | n/a | OK — error toast | Re-click |

## Scan History / Risk Trend / Org Dashboard / Version Diff

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| `scan-history/index.html` | Server-rendered | OK — "No scans yet" + CLI guidance | OK — FAILED rows show the server error message | — |
| `risk-trend` charts (Chart.js) | n/a | OK — placeholder with hint when no scan data | OK — placeholder when Chart.js fails to load | — |
| `risk-trend` AI insight boxes | Server-rendered | OK — "Not enough scan data" dashed card | n/a | — |
| `org-dashboard` trend chart | n/a | OK — noData/loadFailed placeholders | OK | — |
| `org-dashboard` worst-projects table | Server-rendered | OK — "No projects yet." row | n/a | — |
| `version-diff` table | Server-rendered | OK — "No differences found" | n/a | — |
| `version-diff` category tabs (client filter) | n/a | **Fixed** — a tab with 0 entries left a blank table body; added per-tab empty message | n/a | — |
| `version-diff` AI insight | Server-rendered, shown only when present | Absent silently (server knows) | n/a | Deferred — no generating/retry state; insight generation is synchronous server-side |

## Settings tabs

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| `tabs/admin.html` — users table | **Fixed** — "No users." flashed during the initial load; added loading row + `initialLoading` guard | OK | OK — apiError banner (no retry; banner is shared across many actions) | Deferred — retry = reopening the sub-tab; banner is informational |
| `tabs/admin.html` — audit log table | **Fixed** — same flash; added `auditLoading` row | OK — "No audit records." | OK — apiError banner | Filter change / sub-tab re-entry reloads |
| `tabs/admin.html` — snapshot (air-gapped bundle) | OK — importing label | OK — "Nothing imported yet" status | OK — apiError | Re-run import/export |
| `tabs/security.html` — initial settings load | n/a | n/a | **Fixed** — silent failure rendered default values (risk of overwriting config on next save); now sets the apiError banner | Banner visible; Save/Test still available |
| `tabs/security.html` — mail test / saves | OK — testing/saving labels | n/a | OK — inline result banner | Re-test / re-save |
| `tabs/ai.html` | OK (settled — recently refactored, intentionally untouched) | OK | OK — apiError + hint | — |
| `tabs/vcs.html` — connections | OK — loading text | OK — provider cards act as the empty state | **Fixed** — banner had no next action; added Retry button | **Retry** re-runs `loadConnections()` |
| `tabs/cli.html` — API keys | OK — loading row | OK — empty row with guidance | **Fixed** — banner had no next action; added Retry button | **Retry** re-runs `loadKeys()` |
| `tabs/cache.html` — policy load | n/a (form) | n/a | **Fixed** — banner had no next action; added Retry button | **Retry** re-runs `load()` |
| `tabs/cache.html` — Clear All Cache | n/a | n/a | **Fixed** — no `.catch` and the success toast showed even on HTTP failure | apiError banner + Retry |
| `tabs/license-policy.html` — paged table | Partially — blank while first page loads (fetching flag, no spinner row) | OK — "No licenses match your search." | **Fixed** — banner had no next action; added Retry button | **Retry** re-runs `resetAndLoad()` |
| `tabs/license-policy.html` — entry save | n/a | n/a | **Fixed** — `save()` had no `.catch`; network failure was silent | apiError banner + Retry |
| `tabs/webhooks.html` — config form | OK — testing/saving labels | n/a | OK — inline result banner | Re-test / re-save |
| `tabs/webhooks.html` — delivery history | OK — loading text | OK — "No deliveries yet." | **Fixed** — failed fetch rendered as the empty state (misleading); added distinct error block | **Retry** re-runs `loadHistory()` |
| `tabs/reports.html` — branding load/save | OK — saving label | n/a | **Fixed** — load failure had no retry; added Retry shown only for load failures (re-loading after a failed *save* would discard edits) | **Retry** re-runs `load()` |
| `tabs/diagnostics.html` | OK — running label | OK — prompt text | OK — apiError | Run button is the retry |
| `tabs/config-transfer.html` | OK — busy labels | n/a | OK — apiError | Export/Apply buttons are the retry |

## Auth & shared chrome

| Region | Loading | Empty | Failure | Next action on failure |
|---|---|---|---|---|
| `auth/login.html` | Server form | n/a | OK — inline error/disabled/warn banners with guidance | Re-submit |
| `auth/otp-verify.html` | OK — verifying spinner + label | n/a | OK — notice with guidance (mail settings, expiry, invalid code) | Resend button |
| `auth/setup.html` | Server form | n/a | OK — field-level errors | Re-submit |
| `auth/onboarding.html` | n/a — static step links | n/a | n/a | — |
| `auth/change-password.html`, `my-change-password*.html` | OK — submitting state | n/a | OK — notice pattern (same as otp-verify) | Re-submit / resend |
| `fragments/topbar.html` — global search palette | OK — "Searching…" | OK — "No results found." | OK — "Search failed." | Automatic on next keystroke |
| `fragments/topbar.html` — scan delete (version dropdown) | n/a | n/a | **Fixed** — no `.catch`; network failure was silent | `alert()` failure message |

## Cross-cutting notes

- No global `htmx:responseError` handler existed; the Security Center slide-over now handles its own errors locally. Other HTMX usage in the app is limited to that one swap.
- `.min.js` assets are build output (Gradle `minifyJs`); only the source `.js` files were edited.
- New i18n keys (en/ko/ja, kept in sync): `common.retry`, `projects.emptyActive`, `securityCenter.panel.loadFailed`, `gitIntegration.error.reposLoadFailed`, `gitIntegration.error.importFailed`, `gitIntegration.table.empty`, `settings.webhooks.history.error`, `versionDiff.tabEmpty`.
