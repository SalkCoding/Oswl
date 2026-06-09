package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "API key list response — token value is masked")
@Getter
@Builder
public class ApiKeyResponse {

    @Schema(description = "API key primary key", example = "1")
    private final Long    id;

    @Schema(description = "Masked token (only partial prefix and suffix exposed)", example = "oswl_SAM...KEY1")
    private final String  token;

    @Schema(description = "API key label", example = "CI Pipeline")
    private final String  label;

    @Schema(description = "Whether the key is active", example = "true")
    private final boolean active;

    @Schema(description = "Last used timestamp (ISO-8601, null if never used)", example = "2026-05-01T10:23:00")
    private final String  lastUsedAt;

    @Schema(description = "Key creation timestamp (ISO-8601)", example = "2026-04-01T09:00:00")
    private final String  createdAt;

    @Schema(description = "Revocation timestamp (ISO-8601, null if still active)", example = "2026-05-02T14:00:00")
    private final String  revokedAt;
}
