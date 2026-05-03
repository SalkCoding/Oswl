package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight scan-result summary used to populate the version dropdown in the topbar.
 * Each completed ScanResult for a project becomes one entry.
 */
@Getter
@Builder
public class VersionSummaryDto {

    /** ScanResult PK — used as the ?scanId= query param */
    private final Long scanId;

    /**
     * The software version reported by the CLI at scan time (e.g. "1.2.5").
     * Falls back to the scan date string if the CLI did not supply a version.
     */
    private final String version;

    /** Scan date formatted as "yyyy.MM.dd" for display */
    private final String scannedAt;

    /** True when this is the scan currently displayed on the page */
    private final boolean current;
}
