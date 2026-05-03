package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "CVE (security vulnerability) detail")
@Getter
@Builder
@AllArgsConstructor
public class CveDto {

    @Schema(description = "CVE identifier", example = "CVE-2024-11053")
    private final String id;

    @Schema(description = "Severity", example = "CRITICAL", allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW"})
    private final String severity;

    @Schema(description = "CVSS score (0.0 – 10.0)", example = "9.8")
    private final double cvssScore;

    @Schema(description = "Vulnerability type", example = "RCE")
    private final String type;

    @Schema(description = "First discovered date (yyyy-MM-dd)", example = "2024-06-15")
    private final String discoveredOn;

    @Schema(description = "Affected scope", example = "Direct dep.", allowableValues = {"Direct dep.", "Transitive dep."})
    private final String affects;

    @Schema(description = "Fixed version", example = "2.2.1-alpha01")
    private final String fixVersion;

    @Schema(description = "AI-generated risk summary (null if not yet analysed)", example = "An unauthenticated attacker can execute arbitrary server commands via a remote code execution vulnerability.")
    private final String aiSummary;
}
