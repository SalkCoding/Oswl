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

    @Schema(description = "Human-readable status line")
    private final String message;

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

    @Schema(description = "Maximum concurrent import slots", example = "2")
    private final Integer maxConcurrentSlots;
}
