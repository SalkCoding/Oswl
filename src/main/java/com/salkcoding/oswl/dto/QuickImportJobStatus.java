package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Quick Import job status snapshot")
@Getter
@Builder(toBuilder = true)
public class QuickImportJobStatus {

    @Schema(description = "Import pipeline phase")
    public enum Phase {
        QUEUED, CLONING, PARSING, SCANNING, ENRICHING, DONE, FAILED
    }

    @Schema(description = "Job UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private final String jobId;

    @Schema(description = "Current phase", implementation = Phase.class)
    private final Phase phase;

    @Schema(description = "Deprecated — use messageKey; kept for API compatibility")
    private final String message;

    @Schema(description = "Client i18n lookup key for FAILED/DONE detail (see QuickImportMessageKeys)")
    private final String messageKey;

    @Schema(description = "Optional placeholders for messageKey ({0}, {1}, …)")
    private final List<String> messageArgs;

    @Schema(description = "Short repo label (e.g. owner/repo)")
    private final String repoLabel;

    private final Long projectId;
    private final String projectName;

    @Schema(description = "Project CLI API token (full value only on first DONE poll)")
    private final String apiToken;

    @Schema(description = "True when a new API key was created for this import")
    private final Boolean newApiKey;

    private final String ecosystem;
    private final Integer componentCount;
    private final String error;
    private final Long scanResultId;

    @Schema(description = "Queue position when phase is QUEUED; null when running or finished")
    private final Integer queuePosition;

    @Schema(description = "Overall progress 0–100", example = "72")
    private final Integer percent;

    @Schema(description = "Enrichment sub-step: CVE, LICENSE, POSTURE, TREND, or DIFF")
    private final String subPhase;

    @Schema(description = "Recent enrichment detail lines")
    private final List<String> detailLines;

    @Schema(description = "Latest AI preview lines from enrichment")
    private final List<String> aiPreviews;

    @Schema(description = "Number of imports currently executing")
    private final Integer activeSlotsUsed;

    @Schema(description = "Maximum concurrent import slots", example = "3")
    private final Integer maxConcurrentSlots;

    @Schema(description = "Maximum queued imports per user", example = "3")
    private final Integer maxQueuedSlots;
}
