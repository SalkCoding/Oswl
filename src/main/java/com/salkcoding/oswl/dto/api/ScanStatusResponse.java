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
}
