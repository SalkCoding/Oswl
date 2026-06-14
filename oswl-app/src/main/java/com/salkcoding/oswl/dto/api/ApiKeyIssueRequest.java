package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Schema(description = "API key issuance request")
@Getter
@Setter
public class ApiKeyIssueRequest {

    @Schema(description = "API key label (required)", example = "CI Pipeline")
    @NotBlank
    private String        label;

    @Schema(description = "Key expiry timestamp (null = never expires)", example = "2027-01-01T00:00:00")
    private LocalDateTime expiresAt;

    @Schema(description = "When true, issues a CI machine token (passwordless scan submit for bound user)")
    private Boolean machineToken;

    @Schema(description = "User email bound to a machine token (required when machineToken=true)")
    private String boundUserEmail;
}
