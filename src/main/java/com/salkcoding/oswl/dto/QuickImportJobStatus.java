package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Quick Import job status snapshot")
@Getter
@Builder(toBuilder = true)
public class QuickImportJobStatus {

    public enum Phase {
        QUEUED,
        CLONING,
        PARSING,
        SCANNING,
        ENRICHING,
        DONE,
        FAILED
    }

    private final String jobId;
    private final Phase phase;
    private final String message;

    /** Short repo label for multi-job UI (e.g. owner/repo) */
    private final String repoLabel;

    private final Long projectId;
    private final String projectName;
    private final String apiToken;
    private final Boolean newApiKey;
    private final String ecosystem;
    private final Integer componentCount;
    private final String error;
    private final Long scanResultId;

    /** 0 = running or next; null when done/failed; >0 = position in queue */
    private final Integer queuePosition;

    /** Overall progress 0–100 when estimable */
    private final Integer percent;

    /** Fine-grained enrichment step (CVE, LICENSE, …) */
    private final String subPhase;

    /** Recent step detail lines (enrichment sub-steps, hints) */
    private final List<String> detailLines;

    private final List<String> aiPreviews;

    /** Active imports globally (for UI badge) */
    private final Integer activeSlotsUsed;
    private final Integer maxConcurrentSlots;
}
