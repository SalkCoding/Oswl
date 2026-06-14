package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "Admin CLI key issuance request — issues a key for a specific project")
@Getter
@Setter
public class AdminCliKeyIssueRequest {
    @Schema(description = "Project to issue the key for", example = "10")
    private Long   projectId;
    @Schema(description = "API key label", example = "Admin CI")
    private String label;
}
