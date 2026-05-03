package com.salkcoding.oswl.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Schema(description = "Scan payload sent by the CLI client via POST /api/scan")
@Getter
@NoArgsConstructor
public class ScanPayload {

    @Schema(description = "Project version at the time of the scan (required)", example = "1.2.5")
    @NotBlank
    private String version;

    @Schema(description = "List of discovered open-source components")
    @Valid
    private List<ComponentPayload> components;

    @Schema(hidden = true)
    @Setter
    private String rawJson;

    // ── Inner DTOs ────────────────────────────────────────────────────────

    @Schema(description = "Individual open-source component discovered in the scan")
    @Getter
    @NoArgsConstructor
    public static class ComponentPayload {

        @Schema(description = "Library name (required)", example = "lodash")
        @NotBlank
        private String name;

        @Schema(description = "Library version", example = "4.17.21")
        private String version;

        @Schema(description = "Dependency path display string", example = "Direct (1) + Transitive (3) · Projects (2)")
        private String dependencyInfo;

        @Schema(description = "Patchability status", example = "patchable",
                allowableValues = {"patchable", "non-patchable"})
        private String patchability;

        @Schema(description = "License status", example = "OK",
                allowableValues = {"OK", "WARN", "VIOLATION"})
        private String licenseStatus;

        @Schema(description = "License name", example = "MIT")
        private String licenseName;

        @Schema(description = "List of CVEs found in this component")
        @Valid
        private List<CvePayload> cves;
    }

    @Schema(description = "CVE information linked to the component")
    @Getter
    @NoArgsConstructor
    public static class CvePayload {

        @Schema(description = "CVE identifier (required)", example = "CVE-2021-23337")
        @NotBlank
        private String cveId;

        @Schema(description = "Severity", example = "HIGH",
                allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW"})
        private String severity;

        @Schema(description = "CVSS score (0.0 – 10.0)", example = "7.2")
        private Double cvssScore;

        @Schema(description = "Vulnerability type", example = "Injection")
        private String type;

        @Schema(description = "First discovered date (yyyy-MM-dd)", example = "2021-02-15")
        private String discoveredOn;

        @Schema(description = "Affected scope", example = "Direct dep.",
                allowableValues = {"Direct dep.", "Transitive dep."})
        private String affects;

        @Schema(description = "Fixed version", example = "4.17.22")
        private String fixVersion;
    }
}
