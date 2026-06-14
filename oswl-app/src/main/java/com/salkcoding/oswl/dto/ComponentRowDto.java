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

    @Schema(description = "Display name of the user who reviewed this component; null if not reviewed", example = "Alice")
    private final String reviewedByName;

    @Schema(description = "Whether the component has been ignored", example = "false")
    private final boolean ignored;

    @Schema(description = "Whether the component has an active deferral", example = "false")
    private final boolean deferred;

    @Schema(description = "Display name of the user who deferred this component; null if not deferred", example = "Bob")
    private final String deferredByName;

    @Schema(description = "Number of Critical CVEs", example = "2")
    private final int securityCritical;

    @Schema(description = "Number of High CVEs", example = "5")
    private final int securityHigh;

    @Schema(description = "Number of Medium CVEs", example = "12")
    private final int securityMedium;

    @Schema(description = "Number of Low CVEs", example = "30")
    private final int securityLow;

    @Schema(description = "Number of Unscored CVEs (no CVSS score, severity = NONE)", example = "4")
    private final int securityUnscored;

    @Schema(description = "Patchability status", example = "patchable", allowableValues = {"patchable", "non-patchable", "unknown"})
    private final String patchability;

    @Schema(description = "License status", example = "CAUTION", allowableValues = {"PERMITTED", "CAUTION", "RESTRICTED", "UNKNOWN"})
    private final String licenseStatus;

    @Schema(description = "License name", example = "Apache-2.0")
    private final String licenseName;

    @Schema(description = "True when deps.dev reports this is the latest stable version of the package")
    private final Boolean isLatestVersion;

    @Schema(description = "Non-null when deps.dev marks this version as deprecated; contains the reason")
    private final String deprecated;

    @Schema(description = "Latest stable version from deps.dev; non-null only when isLatestVersion is false", example = "2.17.0")
    private final String latestVersion;

    @Schema(description = "Best fix version from CVE/OSV data; non-null when patchability is patchable", example = "2.17.0")
    private final String recommendedFixVersion;

    @Schema(description = "CVEs listed in CISA KEV catalog for this component")
    private final int kevCount;

    @Schema(description = "Highest EPSS exploit probability among CVEs (0.0–1.0)")
    private final Double maxEpssScore;
}
