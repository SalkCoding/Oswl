package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Schema(description = "Create or update a role template")
@Data
public class RoleTemplateRequest {

    @Schema(description = "Template name (must be unique)", example = "Developer")
    @NotBlank @Size(max = 100)
    private String name;

    @Schema(description = "Optional description", example = "Read-only access to projects and scan results")
    private String description;

    @Schema(description = "Permission codes to assign (see GET /api/admin/role-templates/permissions)",
            example = "[\"PROJECT_VIEW\", \"SCAN_HISTORY_VIEW\"]")
    private Set<String> permissions;
}
