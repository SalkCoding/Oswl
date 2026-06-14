package com.salkcoding.oswl.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** Programmatic factory — avoids reflection when building payloads inside the service layer. */
    public static ScanPayload create(String version, List<ComponentPayload> components) {
        ScanPayload p = new ScanPayload();
        p.version    = version;
        p.components = components;
        p.rawJson    = "{}";
        return p;
    }

    /**
     * Submitter email — required for all CLI scans.
     * Used for authentication, attribution, and audit logging.
     */
    @Schema(description = "Submitter email (required for standard API keys; optional for CI machine tokens)")
    @Setter
    private String submitterEmail;

    /**
     * Submitter password — validated server-side via BCrypt before the scan is accepted.
     * WRITE_ONLY: never included in serialized output or stored rawJson.
     * Never logged by Lombok toString.
     */
    @Schema(hidden = true)
    @Setter
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String submitterPassword;

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

        /**
         * Full dependency path trees resolved at scan time.
         * Each inner list is one complete path from the root project (index 0)
         * to this library (last index), inclusive.
         *
         * Example — a transitive dep reached via two routes:
         * <pre>
         * [
         *   [ {"name":"com.example:app","version":"1.0"}, {"name":"commons-lang3","version":"3.12"} ],
         *   [ {"name":"com.example:app","version":"1.0"}, {"name":"spring-web","version":"6.0"}, {"name":"commons-lang3","version":"3.12"} ]
         * ]
         * </pre>
         * Omit or send an empty array for backward-compatibility with older server versions.
         */
        @Schema(description = "Dependency path trees — each inner list is one path from root to this library")
        private List<List<DependencyNodeRef>> dependencyPaths;

        /** Programmatic factory — avoids reflection when building payloads inside the service layer. */
        public static ComponentPayload create(String name, String version, String ecosystem,
                                              String dependencyInfo,
                                              List<List<DependencyNodeRef>> dependencyPaths) {
            ComponentPayload c = new ComponentPayload();
            c.name            = name;
            c.version         = version;
            c.ecosystem       = ecosystem;
            c.dependencyInfo  = dependencyInfo;
            c.dependencyPaths = dependencyPaths;
            return c;
        }
    }

    /**
     * A single package reference in a dependency path (name + version).
     */
    @Schema(description = "A single node in a dependency path")
    @Getter
    @NoArgsConstructor
    public static class DependencyNodeRef {

        @Schema(description = "Package name", example = "org.springframework:spring-web")
        private String name;

        @Schema(description = "Package version", example = "6.0.0")
        private String version;

        /** Programmatic factory — avoids reflection when building payloads inside the service layer. */
        public static DependencyNodeRef create(String name, String version) {
            DependencyNodeRef ref = new DependencyNodeRef();
            ref.name    = name;
            ref.version = version;
            return ref;
        }
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
