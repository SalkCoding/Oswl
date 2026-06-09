package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Admin-view API key entry including project association")
@Getter
@Builder
public class GlobalApiKeyResponse {
    @Schema(description = "API key primary key", example = "1")
    private final Long    id;
    @Schema(description = "Masked token (prefix and suffix only)", example = "oswl_SAM...KEY1")
    private final String  token;
    @Schema(description = "Associated project primary key", example = "10")
    private final Long    projectId;
    @Schema(description = "Associated project name", example = "my-backend")
    private final String  projectName;
    @Schema(description = "API key label", example = "CI Pipeline")
    private final String  label;
    @Schema(description = "Whether the key is active", example = "true")
    private final boolean active;
    @Schema(description = "Key creation timestamp (ISO-8601)", example = "2026-04-01T09:00:00")
    private final String  createdAt;
    @Schema(description = "Last used timestamp (ISO-8601, null if never used)", example = "2026-05-01T10:23:00")
    private final String  lastUsedAt;
}
