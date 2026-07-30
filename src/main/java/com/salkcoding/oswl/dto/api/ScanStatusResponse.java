package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Lightweight status response for a pending or in-progress scan")
@Getter
@Builder
@AllArgsConstructor
public class ScanStatusResponse {

    @Schema(description = "Scan result primary key", example = "42")
    private final Long scanId;

    @Schema(description = "Current scan status", example = "ANALYZING",
            allowableValues = {"PENDING", "SCANNING", "ANALYZING", "COMPLETED", "FAILED"})
    private final String status;

    @Schema(description = "Total number of components in the scan", example = "128")
    private final long componentCount;

    @Schema(description = "AI enrichment progress, tracked separately from status — a scan can be " +
            "COMPLETED while this is still PENDING/RUNNING (AI summaries generate in the background)",
            example = "RUNNING",
            allowableValues = {"NOT_APPLICABLE", "PENDING", "RUNNING", "COMPLETED", "FAILED"})
    private final String aiStatus;

    @Schema(description = "AI security posture insight, once generated (null until aiStatus reaches COMPLETED)")
    private final String securityPostureInsight;
}
