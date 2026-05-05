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

        @Schema(description = "Library name (required)", example = "org.springframework:spring-core")
        @NotBlank
        private String name;

        @Schema(description = "Library version", example = "6.1.4")
        private String version;

        /**
         * Package ecosystem — must match deps.dev system values.
         * Examples: MAVEN, NPM, PYPI, GO, CARGO, NUGET, RUBYGEMS
         */
        @Schema(description = "Package ecosystem (required)", example = "MAVEN",
                allowableValues = {"MAVEN", "NPM", "PYPI", "GO", "CARGO", "NUGET", "RUBYGEMS"})
        @NotBlank
        private String ecosystem;

        @Schema(description = "Dependency path display string", example = "Direct (1) + Transitive (3)")
        private String dependencyInfo;
    }

    /**
     * Kept for backward-compatibility with older CLI versions that still send CVE data.
     * New pipeline ignores this; all vulnerability analysis is done server-side.
     */
    @Schema(description = "Legacy CVE payload (ignored by the new pipeline)")
    @Getter
    @NoArgsConstructor
    public static class CvePayload {
        private String cveId;
        private String severity;
        private Double cvssScore;
        private String fixVersion;
    }
}
