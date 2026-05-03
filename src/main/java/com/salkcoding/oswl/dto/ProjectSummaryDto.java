package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Project list card — includes security and license summary figures based on the latest completed scan")
@Getter
@Builder
@AllArgsConstructor
public class ProjectSummaryDto {

    @Schema(description = "Project primary key", example = "1")
    private final Long id;

    @Schema(description = "Project name", example = "My-Service")
    private final String name;

    @Schema(description = "Latest scan version", example = "1.2.5")
    private final String version;

    @Schema(description = "Last scan date (yyyy.MM.dd)", example = "2026.05.01")
    private final String lastScanned;

    @Schema(description = "Number of Critical CVEs", example = "3")
    private final int securityCritical;

    @Schema(description = "Number of High CVEs", example = "8")
    private final int securityHigh;

    @Schema(description = "Number of Medium CVEs", example = "20")
    private final int securityMedium;

    @Schema(description = "Number of Low CVEs", example = "55")
    private final int securityLow;

    @Schema(description = "Number of license VIOLATIONs (Critical)", example = "1")
    private final int licenseCritical;

    @Schema(description = "Number of license WARNs (High)", example = "3")
    private final int licenseHigh;

    @Schema(description = "Number of license Mediums (currently unused, reserved)", example = "0")
    private final int licenseMedium;

    @Schema(description = "Number of license OKs (Low)", example = "18")
    private final int licenseLow;

    @Schema(description = "GitHub source in owner/repo format — null if not imported from GitHub", example = "acme/api-service")
    private final String githubRepo;

    @Schema(description = "Formatted GitHub import timestamp — null if not imported from GitHub", example = "2026.05.03 14:05")
    private final String importedAt;

    @Schema(description = "Stable project UUID used as the CLI API key identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private final String projectUuid;
}
