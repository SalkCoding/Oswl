package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.api.AdminCliKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.GlobalApiKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Admin — CLI Keys", description = "Global CLI API key management across all projects. Requires SYSTEM_ADMIN role.")
public interface AdminCliKeyControllerSpec {

    @Operation(summary = "List all CLI API keys",
        description = "Returns every API key in the system across all projects. Tokens are masked — only the first 9 and last 4 characters are shown.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global API key list",
            content = @Content(schema = @Schema(implementation = GlobalApiKeyResponse.class)))
    })
    ResponseEntity<List<GlobalApiKeyResponse>> listAll();

    @Operation(summary = "Issue a CLI API key for a project",
        description = "Creates a new active API key for the specified project. The full token is returned **only once** — store it securely immediately.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Issued key (token shown once)",
            content = @Content(schema = @Schema(implementation = ApiKeyIssueResponse.class))),
        @ApiResponse(responseCode = "400", description = "Missing or invalid `projectId`", content = @Content)
    })
    ResponseEntity<ApiKeyIssueResponse> issue(@RequestBody AdminCliKeyIssueRequest request);

    @Operation(summary = "Toggle a CLI API key's active status",
        description = "Activates an inactive key or revokes an active key. Revoked keys are rejected by the scan API immediately.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Status toggled", content = @Content),
        @ApiResponse(responseCode = "404", description = "Key not found", content = @Content)
    })
    ResponseEntity<Void> toggle(
        @Parameter(description = "API key ID", example = "5", required = true) @PathVariable Long keyId
    );
}
