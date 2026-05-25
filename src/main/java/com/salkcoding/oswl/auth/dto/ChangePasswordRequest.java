package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Forced password change request (required on first login for invited users)")
@Data
public class ChangePasswordRequest {
    @Schema(description = "Current (temporary) password", example = "Temp@1234")
    private String currentPassword;
    @Schema(description = "New password (must meet the configured minimum length)", example = "NewSecure@99")
    private String newPassword;
    @Schema(description = "New password confirmation (must match newPassword)", example = "NewSecure@99")
    private String confirmPassword;
}
