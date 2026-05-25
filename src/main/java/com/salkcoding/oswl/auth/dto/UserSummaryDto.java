package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Summary of a user account")
@Data
@Builder
@AllArgsConstructor
public class UserSummaryDto {
    @Schema(description = "User primary key", example = "2")
    private Long id;
    @Schema(description = "Login email address", example = "alice@example.com")
    private String email;
    @Schema(description = "Display name shown in the UI", example = "Alice")
    private String displayName;
    @Schema(description = "Whether the user has the SYSTEM_ADMIN built-in role", example = "false")
    private boolean systemAdmin;
    @Schema(description = "Whether the account is active (can log in)", example = "true")
    private boolean enabled;
    @Schema(description = "Account creation timestamp (ISO-8601)", example = "2026-01-15T09:00:00")
    private LocalDateTime createdAt;
    @Schema(description = "Last successful login timestamp (ISO-8601, null if never)", example = "2026-05-20T08:30:00")
    private LocalDateTime lastLoginAt;
    @Schema(description = "Role templates assigned to this user")
    private List<RoleTemplateRefDto> roleTemplates;
}
