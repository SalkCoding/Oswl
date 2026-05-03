package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Security Center component row — CVE aggregation and license info for a single component")
@Getter
@Builder
@AllArgsConstructor
public class ComponentRowDto {

    @Schema(description = "Component primary key", example = "3")
    private final Long id;

    @Schema(description = "Library name", example = "jackson-databind")
    private final String name;

    @Schema(description = "Library version", example = "2.15.0")
    private final String version;

    @Schema(description = "Dependency path display string", example = "Direct (2) + Transitive (5) · Projects (3)")
    private final String dependencyInfo;

    @Schema(description = "Whether the component has been reviewed", example = "false")
    private final boolean reviewed;

    @Schema(description = "Whether the component has been ignored", example = "false")
    private final boolean ignored;

    @Schema(description = "Number of Critical CVEs", example = "2")
    private final int securityCritical;

    @Schema(description = "Number of High CVEs", example = "5")
    private final int securityHigh;

    @Schema(description = "Number of Medium CVEs", example = "12")
    private final int securityMedium;

    @Schema(description = "Number of Low CVEs", example = "30")
    private final int securityLow;

    @Schema(description = "Patchability status", example = "patchable", allowableValues = {"patchable", "non-patchable", "unknown"})
    private final String patchability;

    @Schema(description = "License status", example = "WARN", allowableValues = {"OK", "WARN", "VIOLATION"})
    private final String licenseStatus;

    @Schema(description = "License name", example = "Apache-2.0")
    private final String licenseName;
}
