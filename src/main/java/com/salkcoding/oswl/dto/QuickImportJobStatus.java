package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Snapshot of a Quick Import job returned by the status-poll endpoint.
 */
@Schema(description = "Quick Import job status snapshot returned by the status-poll endpoint")
@Getter
@Builder
public class QuickImportJobStatus {

    @Schema(description = "Import job processing phase")
    public enum Phase {
        /** Job was accepted and is waiting to start */
        QUEUED,
        /** Cloning the repository */
        CLONING,
        /** Parsing dependency files */
        PARSING,
        /** Sending results to the scan pipeline */
        SCANNING,
        /** Vulnerability and AI enrichment running asynchronously */
        ENRICHING,
        /** Import and scan completed */
        DONE,
        /** Import or scan failed */
        FAILED
    }

    @Schema(description = "Unique job identifier", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private final String jobId;
    @Schema(description = "Current processing phase", example = "CLONING")
    private final Phase  phase;

    @Schema(description = "Human-readable status message", example = "Cloning repository...")
    /** Human-readable status message (one line). */
    private final String message;

    @Schema(description = "Created or matched project primary key (available once phase >= SCANNING)", example = "10")
    /** projectId created/found — available once phase ≥ SCANNING */
    private final Long projectId;

    @Schema(description = "Project name", example = "my-backend")
    /** Project name */
    private final String projectName;

    @Schema(description = "API key token (shown once on DONE; masked on subsequent polls)",
            example = "oswl_aBcDeFgHiJkLmNoPqRsTuVwXyZ123456")
    /**
     * API key token — shown to the user once on DONE.
     * Masked on subsequent polls (only available in the immediate DONE response).
     */
    private final String apiToken;

    @Schema(description = "Whether the API key was newly issued (true) or reused (false)", example = "true")
    /** Whether the API key was newly issued (true) or reused (false). */
    private final Boolean newApiKey;

    /** Detected ecosystem (MAVEN / NPM / PYPI / CARGO / GRADLE / UNKNOWN) */
    private final String ecosystem;

    /** Number of dependency components parsed */
    private final Integer componentCount;

    /** Error detail — set when phase = FAILED */
    private final String error;

    /** ScanResult ID — set during ENRICHING phase so the poller can check completion */
    private final Long scanResultId;
}
