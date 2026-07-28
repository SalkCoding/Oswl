# Risk Trend

The Risk Trend page visualizes how the security and license posture of a project has changed across multiple scans over time.

URL: `/projects/{projectId}/risk-trend`

---

## What the Charts Show

### CVE Severity Trend

A line chart showing the count of CVEs at each severity level — CRITICAL, HIGH, MEDIUM, LOW — for each scan, ordered by scan date. CVEs without a severity score are shown as a separate **Unscored** series.

This lets you immediately see whether your team is making progress (lines falling) or regressing (lines rising).

### License Risk Trend

A line chart tracking the count of components with each license status — RESTRICTED, CAUTION, PERMITTED, UNKNOWN — across scans.

---

## Scan Limit

By default, the risk trend displays the **10 most recent scans**. This limit is configurable:

```yaml
# application.yaml
oswl:
  risk-trend:
    limit: 10
```

Or via environment variable:

```bash
OSWL_RISK_TREND_LIMIT=20
```

---

## AI Insights on Risk Trend

If an AI provider is configured, two AI narratives are shown below each chart:

| Insight | Content |
|---|---|
| **Security Risk Trend** | Paragraph comparing the current scan to the previous one — e.g. "CRITICAL CVEs decreased from 3 to 1, suggesting the team successfully patched Log4Shell and Spring4Shell." |
| **License Risk Trend** | Similar narrative for license posture changes — e.g. "One new RESTRICTED license (GPL-3.0) was introduced via an indirect dependency." |

These insights are generated once per scan during the enrichment phase and cached on the scan record. They are **not** regenerated when you load the page. Both narratives are produced as part of a single combined AI call that also generates the security posture and version-diff summaries, reducing the number of AI calls per scan. If AI was not configured at scan time, insights for recent scans are backfilled automatically when a provider is enabled; changing the AI prompt language regenerates them.

---

## Reading the Charts

* Each point on the X-axis represents **one scan** identified by its project version.
* Hovering over a data point shows the exact counts and the scan version.

---

## Tips

* Run scans **before and after** applying dependency upgrades to see the improvement reflected in the trend.
* Use the scan version labels on the X-axis to correlate spikes with specific releases or dependency updates.
* If AI insights say "no change", it means the security and license counts are virtually unchanged from the previous scan — useful for confirming a re-scan produced consistent results.
