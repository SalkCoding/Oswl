package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Org dashboard ranking row — per-project risk summary based on the latest completed scan")
@Getter
@Builder
@AllArgsConstructor
public class OrgProjectRiskDto {

    @Schema(description = "Project primary key", example = "1")
    private final Long id;

    @Schema(description = "Project name", example = "My-Service")
    private final String name;

    @Schema(description = "Latest scan version — null when never scanned", example = "1.2.5")
    private final String version;

    @Schema(description = "Last scan date (yyyy.MM.dd) — null when never scanned", example = "2026.05.01")
    private final String lastScanned;

    @Schema(description = "Whether the project has at least one completed scan", example = "true")
    private final boolean scanned;

    @Schema(description = "Number of Critical CVEs", example = "3")
    private final int securityCritical;

    @Schema(description = "Number of High CVEs", example = "8")
    private final int securityHigh;

    @Schema(description = "Number of Medium CVEs", example = "20")
    private final int securityMedium;

    @Schema(description = "Number of Low CVEs", example = "55")
    private final int securityLow;

    @Schema(description = "Number of Unscored CVEs (no CVSS score, severity = NONE)", example = "2")
    private final int securityUnscored;

    @Schema(description = "Number of license violations (RESTRICTED components)", example = "1")
    private final int licenseViolations;

    @Schema(description = "Number of license warnings (CAUTION components)", example = "3")
    private final int licenseWarnings;

    @Schema(description = "KEV-listed CVEs on components that are neither reviewed nor deferred", example = "2")
    private final int kevUnaddressed;

    @Schema(description = "Owning team id — null when the project has no team", example = "1")
    private final Long teamId;

    @Schema(description = "Owning team name — null when the project has no team", example = "Payments")
    private final String teamName;
}
