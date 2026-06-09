package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Quick Import jobs for the current user plus queue capacity snapshot")
@Getter
@Builder
public class QuickImportJobsResponse {

    @Schema(description = "All in-memory jobs owned by the user (including recent finished jobs)")
    private final List<QuickImportJobStatus> jobs;

    @Schema(description = "Imports currently executing instance-wide")
    private final int activeSlotsUsed;

    @Schema(description = "Maximum concurrent import slots (instance-wide)")
    private final int maxConcurrentSlots;

    @Schema(description = "This user's jobs waiting in QUEUED phase")
    private final int userQueuedCount;

    @Schema(description = "This user's jobs actively running (not queued or finished)")
    private final int userRunningCount;

    @Schema(description = "Maximum queued imports allowed per user")
    private final int maxQueuedSlots;
}
