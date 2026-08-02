package com.salkcoding.oswl.dto.gate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body for {@code POST /api/scan/gate}. All fields are optional:
 * threshold fields override the server defaults, and the {@code github} block is
 * present only when the caller wants the result posted back to a pull request.
 */
@Schema(description = "PR/CI security-gate evaluation request")
public record GateRequest(
        @Schema(description = "Scan to gate; omit for the latest completed scan of the API key's project", example = "42")
        Long scanId,
        @Schema(description = "Fail on CVEs at this severity or higher", example = "HIGH",
                allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW"})
        String failOnSeverity,
        @Schema(description = "Fail on any CISA KEV-listed CVE", example = "true")
        Boolean failOnKev,
        @Schema(description = "Fail on CVEs with EPSS ≥ this value; negative disables", example = "0.5")
        Double failOnEpss,
        @Schema(description = "Fail on RESTRICTED-license components", example = "true")
        Boolean failOnLicenseViolation,
        @Schema(description = "Consider only findings absent from the previous completed scan", example = "true")
        Boolean onlyNew,
        @Schema(description = "Fail only on CVEs whose library is reachable per bytecode call-graph analysis " +
                "(Java only; non-Java components and unanalyzed Java components are never blocking under this option)",
                example = "false")
        Boolean onlyReachable,
        @Schema(description = "Optional GitHub target — when present, the result is posted as a PR comment and/or Check Run")
        GitHubTarget github
) {
    /**
     * GitHub posting target. The {@code token} is supplied by the CI job (e.g. Actions
     * {@code GITHUB_TOKEN}) and is never stored — it is used only for this request.
     */
    @Schema(description = "GitHub posting target for the gate result")
    public record GitHubTarget(
            @Schema(description = "GitHub token with permission to comment / create check runs (e.g. CI GITHUB_TOKEN)")
            String token,
            @Schema(description = "Repository owner", example = "acme")
            String owner,
            @Schema(description = "Repository name", example = "api-service")
            String repo,
            @Schema(description = "Pull request number to comment on; omit to skip the comment", example = "128")
            Integer prNumber,
            @Schema(description = "Head commit SHA for the Check Run; omit to skip the check run", example = "a1b2c3d")
            String headSha,
            @Schema(description = "GitHub Enterprise server URL; omit for github.com")
            String serverUrl
    ) {}
}
