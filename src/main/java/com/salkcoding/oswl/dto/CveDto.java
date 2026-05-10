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

    @Schema(description = "CVE identifier (e.g. CVE-2024-11053 or GHSA-xxx)", example = "CVE-2024-11053")
    private final String id;

    @Schema(description = "GHSA advisory ID from deps.dev", example = "GHSA-jfh8-c2jp-hdp8")
    private final String ghsaId;

    @Schema(description = "Vulnerability title from deps.dev", example = "Remote Code Execution in log4j")
    private final String title;

    @Schema(description = "Severity (NONE = Unscored, i.e. no CVSS score)", example = "CRITICAL", allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE"})
    private final String severity;

    @Schema(description = "CVSS 3.x base score (0.0 – 10.0)", example = "9.8")
    private final double cvssScore;

    @Schema(description = "CVSS 3.x vector string", example = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H")
    private final String cvss3Vector;

    @Schema(description = "CWE identifier (populated from NVD when API key is configured)", example = "CWE-20")
    private final String cweId;

    @Schema(description = "Vulnerability summary from OSV", example = "Improper input validation allows remote code execution.")
    private final String summary;

    @Schema(description = "Fixed version", example = "2.2.1")
    private final String fixVersion;

    @Schema(description = "AI-generated risk summary (null if not yet analysed)")
    private final String aiSummary;
}

