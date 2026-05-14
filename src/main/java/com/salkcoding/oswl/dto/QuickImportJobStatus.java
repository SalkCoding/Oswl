package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Snapshot of a Quick Import job returned by the status-poll endpoint.
 */
@Getter
@Builder
public class QuickImportJobStatus {

    public enum Phase {
        /** Job was accepted and is waiting to start */
        QUEUED,
        /** Cloning the repository */
        CLONING,
        /** Parsing dependency files */
        PARSING,
        /** Sending results to the scan pipeline */
        SCANNING,
        /** Import and scan completed */
        DONE,
        /** Import or scan failed */
        FAILED
    }

    private final String jobId;
    private final Phase  phase;

    /** Human-readable status message (one line). */
    private final String message;

    /** projectId created/found — available once phase ≥ SCANNING */
    private final Long projectId;

    /** Project name */
    private final String projectName;

    /**
     * API key token — shown to the user once on DONE.
     * Masked on subsequent polls (only available in the immediate DONE response).
     */
    private final String apiToken;

    /** Whether the API key was newly issued (true) or reused (false). */
    private final Boolean newApiKey;

    /** Detected ecosystem (MAVEN / NPM / PYPI / CARGO / GRADLE / UNKNOWN) */
    private final String ecosystem;

    /** Number of dependency components parsed */
    private final Integer componentCount;

    /** Error detail — set when phase = FAILED */
    private final String error;
}
