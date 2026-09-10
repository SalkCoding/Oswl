package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Org dashboard team roll-up row — per-team risk aggregate with its project rows for drilldown")
@Getter
@Builder
@AllArgsConstructor
public class OrgTeamRiskDto {

    @Schema(description = "Team primary key — null for the synthetic 'no team' bucket", example = "1")
    private final Long teamId;

    @Schema(description = "Team name", example = "Payments")
    private final String teamName;

    @Schema(description = "Number of projects in the team", example = "12")
    private final int projectCount;

    @Schema(description = "Number of projects with at least one completed scan", example = "10")
    private final int scannedProjects;

    @Schema(description = "Number of Critical CVEs across the team's latest completed scans", example = "4")
    private final int securityCritical;

    @Schema(description = "Number of High CVEs", example = "9")
    private final int securityHigh;

    @Schema(description = "Number of Medium CVEs", example = "21")
    private final int securityMedium;

    @Schema(description = "Number of Low CVEs", example = "40")
    private final int securityLow;

    @Schema(description = "Number of Unscored CVEs (no CVSS score)", example = "1")
    private final int securityUnscored;

    @Schema(description = "Number of license violations (RESTRICTED components)", example = "2")
    private final int licenseViolations;

    @Schema(description = "KEV-listed CVEs on components that are neither reviewed nor deferred", example = "3")
    private final int kevUnaddressed;

    @Schema(description = "The team's project risk rows (same shape as the worst-project ranking) for drilldown")
    private final List<OrgProjectRiskDto> projects;
}
