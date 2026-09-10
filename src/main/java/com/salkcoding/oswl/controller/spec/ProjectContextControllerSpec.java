package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "Project Context", description = "Per-project settings that influence AI enrichment prompts.")
public interface ProjectContextControllerSpec {

    @Schema(description = "Deployment profile for AI CVE triage context")
    record DeploymentProfileRequest(
            @Schema(description = "Deployment context", example = "COMMERCIAL_PRODUCT",
                    implementation = DeploymentProfile.class)
            @NotNull DeploymentProfile deploymentProfile
    ) {}

    @Schema(description = "Project tag labels update")
    record TagsRequest(
            @Schema(description = "Comma-separated tag labels (max 500 chars)", example = "backend,pci")
            @Size(max = 500, message = "Tags must not exceed 500 characters")
            String tags
    ) {}

    @Operation(summary = "Set project deployment profile",
            description = """
                    Updates the deployment profile used when generating AI CVE batch summaries for this project.
                    Overrides the instance default from AI preferences when set.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated",
                    content = @Content(schema = @Schema(example = "{\"deploymentProfile\": \"COMMERCIAL_PRODUCT\"}"))),
            @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<Map<String, String>> updateDeploymentProfile(
            @Parameter(description = "Project ID", required = true) @PathVariable Long projectId,
            @RequestBody DeploymentProfileRequest request
    );
}
