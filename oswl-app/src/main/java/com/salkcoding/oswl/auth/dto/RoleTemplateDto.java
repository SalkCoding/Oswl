package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Schema(description = "Role template with assigned permissions")
@Data
@Builder
@AllArgsConstructor
public class RoleTemplateDto {
    @Schema(description = "Role template primary key", example = "1")
    private Long id;
    @Schema(description = "Template name", example = "Developer")
    private String name;
    @Schema(description = "Optional description", example = "Read-only access to projects and scan results")
    private String description;
    @Schema(description = "Whether this is a system built-in template that cannot be deleted", example = "false")
    private boolean builtIn;
    @Schema(description = "Set of permission codes granted by this template", example = "[\"PROJECT_VIEW\", \"SCAN_HISTORY_VIEW\"]")
    private Set<String> permissions;
    @Schema(description = "Number of users currently assigned this template", example = "3")
    private long userCount;
}
