# Organization risk briefing

Open **Organization dashboard → Organization risk briefing** for a concise, printable summary of portfolio risks and follow-up actions. It uses each project's latest completed scan and lists up to five priority projects, critical/high findings, untriaged KEV findings and license-policy violations. The print button uses browser printing/PDF output.

This is a decision-oriented view over the existing organization aggregates. It does not generate an AI opinion or infer that a reviewed/deferred item has been fixed. Counts represent finding occurrences rather than unique CVEs. Scan dates and analysis coverage must be checked before treating a result as current; the briefing states these limits explicitly. Projects without completed scans remain visible in the coverage denominator.

Both controller and service require `ORG_DASHBOARD_VIEW` (system administrators also qualify). Links to individual projects still require normal project access. Access is audited as `ORG_SUMMARY.VIEW`.

Chromium tests verify English/Korean/Japanese rendering, 390px layout, serious/critical axe violations, PDF rendering and denied access. These are browser tests, not physical-device certification.
