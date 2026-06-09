package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Response to a CLI scan submission")
@Getter
@Builder
public class ScanResponse {

    @Schema(description = "Created ScanResult primary key", example = "42")
    private final Long   scanId;

    @Schema(description = "Project primary key associated with the scan", example = "1")
    private final Long   projectId;

    @Schema(description = "Project version submitted with the scan", example = "1.3.0")
    private final String version;

    @Schema(description = "Scan status", example = "COMPLETED", allowableValues = {"PENDING", "COMPLETED", "FAILED"})
    private final String status;

    @Schema(description = "Processing result message", example = "Scan received successfully")
    private final String message;
}
