package com.salkcoding.oswl.dto.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** POST /api/projects/{projectId}/keys 요청 바디 */
@Getter
@Setter
public class ApiKeyIssueRequest {
    @NotBlank
    private String        label;
    private LocalDateTime expiresAt;
}
