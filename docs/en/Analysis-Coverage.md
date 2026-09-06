# Analysis coverage

Gate responses include `coverage`: total and unanalysed component counts, scan completion, availability of retained detail, and `complete`. An unfinished or archived scan, or any component without completed vulnerability lookup evidence, produces a `COVERAGE / INCOMPLETE_ANALYSIS` violation and exit code 1. Ignore/defer, reachability and newly introduced finding filters do not hide missing analysis.

Component rows and detail pages expose the latest lookup time and source outcomes: `RESOLVED`, `UNAVAILABLE`, `UNSUPPORTED`, or `NOT_CONFIGURED`. A successful empty response is resolved. A timeout, upstream error, malformed response, or incomplete pagination is unavailable. Any unavailable source keeps coverage incomplete, even when older CVEs or a fetched timestamp remain. Successful retry replaces the outcome. Legacy cached rows without outcome metadata retain their previous completion state until normal refresh.

Coverage describes available analysis evidence, not a guarantee that every upstream database is current or that a package is safe. NVD and GitHub responses exceeding a single requested page currently fail coverage instead of silently accepting partial results.

Offline exports preserve confirmed empty results and unresolved component markers separately. Imports retain severity, CVSS vectors/scores and match confidence. An unresolved marker wins over retained findings; missing entries do not count as completed queries.

Deployment adds the two nullable library columns in `V32__vulnerability_lookup_outcomes.sql`. Existing data is retained. PostgreSQL runtime validation still requires a dedicated test database; local integration verification uses H2.
