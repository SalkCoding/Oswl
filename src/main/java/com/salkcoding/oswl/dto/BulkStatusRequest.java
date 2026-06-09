package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for bulk reviewed/ignored state update on security-center components")
public record BulkStatusRequest(
        @Schema(description = "List of ScanComponent IDs to update", example = "[1, 2, 3]", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Long> ids,

        @Schema(description = "Set to true/false to mark components as reviewed or unreviewed; null = no change", example = "true")
        Boolean reviewed,

        @Schema(description = "Set to true/false to mark components as ignored or unignored; null = no change", example = "false")
        Boolean ignored
) {}
