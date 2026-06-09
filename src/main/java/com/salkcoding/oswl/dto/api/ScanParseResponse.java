package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@Schema(description = "Server-side dependency parse result for CLI manifest upload")
public class ScanParseResponse {

    @Schema(description = "Primary detected ecosystem", example = "MAVEN")
    private final String ecosystem;

    @Schema(description = "Number of unique components discovered", example = "128")
    private final int componentCount;

    @Schema(description = "Parsed components (same shape as ScanPayload.components)")
    private final List<ScanPayload.ComponentPayload> components;
}
