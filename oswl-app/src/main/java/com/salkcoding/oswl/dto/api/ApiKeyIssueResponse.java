package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "API key issuance response — full token is returned only once at issuance")
@Getter
@Builder
public class ApiKeyIssueResponse {

    @Schema(description = "API key primary key", example = "2")
    private final Long   id;

    @Schema(description = "Full token (exposed only at issuance — store it securely)",
            example = "oswl_aBcDeFgHiJkLmNoPqRsTuVwXyZ123456")
    private final String token;

    @Schema(description = "API key label", example = "Local Dev")
    private final String label;

    @Schema(description = "Key creation timestamp (ISO-8601)", example = "2026-05-02T23:00:00")
    private final String createdAt;

    @Schema(description = "Guidance message", example = "API key issued. Store this token securely — it won't be shown again.")
    private final String message;
}
