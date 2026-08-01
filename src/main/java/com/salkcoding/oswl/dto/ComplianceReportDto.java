package com.salkcoding.oswl.dto;

import java.util.List;

/**
 * Aggregated data for the printable compliance report (CRA / ISMS-P evidence).
 * Assembled by {@code ComplianceReportService} from the latest completed scan —
 * no new persistence, all figures are derived from existing scan/CVE/deferral data.
 */
public record ComplianceReportDto(
        String projectName,
        String generatedAt,
        boolean hasScan,

        // ── Component inventory / SBOM basis ──
        String scanVersion,
        String scannedAt,
        int totalComponents,

        // ── CRA readiness ──
        int kevTotal,
        int kevUnresolved,
        int criticalCves,
        int highCves,
        int mediumCves,
        int lowCves,
        int unscoredCves,

        // ── Triage SLA ──
        int reviewedComponents,
        int deferredComponents,
        int untriagedRiskComponents,
        String avgTriageTime,

        // ── License compliance ──
        int licenseViolations,
        int licenseWarnings,
        int licenseUnknown,
        int licensePermitted,

        // ── Detail rows for the KEV table ──
        List<KevRow> kevRows
) {
    /** One actively-exploited (KEV) vulnerability row for the report's KEV table. */
    public record KevRow(
            String cveId,
            String componentName,
            String componentVersion,
            String severity,
            String fixVersion,
            String triageState
    ) {}
}
