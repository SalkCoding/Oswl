package com.salkcoding.oswl.dto.gate;

import java.util.List;

/**
 * Result of a PR/CI security gate evaluation.
 *
 * CI contract: {@code exitCode} is 0 when {@code passed} is true, 1 otherwise —
 * a CI job maps it directly to its process exit code. {@code commentMarkdown} is a
 * ready-to-post PR comment body.
 */
public record GateResultDto(
        boolean passed,
        int exitCode,
        String projectName,
        Long scanId,
        String scanVersion,
        String baselineVersion,
        boolean onlyNew,
        boolean onlyReachable,
        Thresholds thresholds,
        int evaluatedCount,
        int newVulnerabilityCount,
        List<Violation> violations,
        String summary,
        String commentMarkdown,
        GitHubResult github
) {
    /** The gate thresholds that were applied (echoed for transparency in the comment). */
    public record Thresholds(
            String failOnSeverity,
            boolean failOnKev,
            Double failOnEpss,
            boolean failOnLicenseViolation,
            boolean failOnSecrets
    ) {}

    /** One reason the gate would fail (or a notable finding when the gate passes). */
    public record Violation(
            String type,          // CVE | LICENSE | MALICIOUS | SECRET
            String id,            // CVE/GHSA id, or license name
            String component,     // name@version
            String severity,      // CVE severity or LICENSE status
            Double epss,          // nullable
            boolean kev,
            String reason,        // human-readable why-it-fails
            boolean isNew         // absent from the baseline scan
    ) {}

    /** Populated only when GitHub posting was requested and attempted. */
    public record GitHubResult(
            boolean commentPosted,
            String commentUrl,
            boolean checkRunPosted,
            String checkRunUrl,
            String error
    ) {}
}
