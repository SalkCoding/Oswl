package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.ProjectWebhookConfigDto;
import com.salkcoding.oswl.dto.UpdateProjectWebhookRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Project Webhook", description = "Configure VCS push webhook for automatic re-scan.")
@SecurityRequirement(name = "sessionAuth")
public interface ProjectWebhookControllerSpec {

    @Operation(summary = "Get webhook configuration (secret not returned)")
    ResponseEntity<ProjectWebhookConfigDto> get(
            @Parameter(description = "Project ID") Long projectId);

    @Operation(summary = "Update webhook settings; new secret returned once when rotated")
    ResponseEntity<ProjectWebhookConfigDto> update(
            @Parameter(description = "Project ID") Long projectId,
            UpdateProjectWebhookRequest request);
}
