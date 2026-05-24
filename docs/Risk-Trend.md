# Risk Trend

The Risk Trend page visualizes how the security and license posture of a project has changed across multiple scans over time.

URL: `/projects/{id}/risk-trend`

---

## What the Charts Show

### CVE Severity Trend

A stacked bar (or line) chart showing the count of CVEs at each severity level — CRITICAL, HIGH, MEDIUM, LOW — for each scan, ordered by scan date.

This lets you immediately see whether your team is making progress (bars shrinking) or regressing (bars growing).

### License Risk Trend

A chart tracking the count of components with each license status — RESTRICTED, CAUTION, PERMITTED, UNKNOWN — across scans.

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

If an AI provider is configured, two AI narratives are shown at the top of the page:

| Insight | Content |
|---|---|
| **Security Risk Trend** | Paragraph comparing the current scan to the previous one — e.g. "CRITICAL CVEs decreased from 3 to 1, suggesting the team successfully patched Log4Shell and Spring4Shell." |
| **License Risk Trend** | Similar narrative for license posture changes — e.g. "One new RESTRICTED license (GPL-3.0) was introduced via an indirect dependency." |

These insights are generated once per scan during the enrichment phase and cached on the scan record. They are **not** regenerated when you load the page.

---

## Reading the Charts

* Each bar/point on the X-axis represents **one scan** identified by its scan date and project version.
* Hovering over a data point shows the exact counts and the scan timestamp.
* Click a scan label to navigate directly to that scan's Security Center or License Analysis.

---

## Tips

* Run scans **before and after** applying dependency upgrades to see the improvement reflected in the trend.
* Use the scan timestamp annotations to correlate spikes with specific releases or dependency updates.
* If AI insights say "no change", that means this scan's component list is identical to the previous one — useful for confirming a re-scan produced consistent results.
