package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "Admin-initiated user creation request")
@Data
public class CreateUserRequest {

    @Schema(description = "Display name shown in the UI", example = "Alice")
    @NotBlank @Size(max = 100)
    private String displayName;

    @Schema(description = "Login email address", example = "alice@example.com")
    @NotBlank @Email
    private String email;

    @Schema(description = "Temporary password the user must change on first login", example = "Temp@1234")
    @NotBlank @Size(min = 8)
    private String temporaryPassword;

    @Schema(description = "IDs of role templates to assign (empty = no roles)", example = "[1, 3]")
    private List<Long> templateIds;
}
