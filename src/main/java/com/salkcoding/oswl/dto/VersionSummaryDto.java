package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight scan-result summary used to populate the version dropdown in the topbar.
 * Each completed ScanResult for a project becomes one entry.
 */
@Schema(description = "Completed scan summary entry — used to populate the version dropdown in the project topbar")
@Getter
@Builder
public class VersionSummaryDto {

    @Schema(description = "ScanResult primary key — used as the ?scanId= query param", example = "42")
    private final Long scanId;

    @Schema(description = "Software version reported by the CLI at scan time; falls back to scan date if not supplied", example = "1.2.5")
    private final String version;

    @Schema(description = "Scan date formatted as yyyy.MM.dd", example = "2026.05.01")
    private final String scannedAt;

    @Schema(description = "True when this entry is the scan currently shown on the page", example = "true")
    private final boolean current;
}
