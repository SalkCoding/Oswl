package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.api.ApiKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.ApiKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Tag(name = "API Key Management", description = "Issue and revoke CLI authentication keys scoped to a project.")
public interface ApiKeyControllerSpec {

    @Operation(
        summary = "List API keys for a project",
        description = "Returns all API keys associated with the given project. Token values are masked — only the prefix and suffix are shown."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Key list returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiKeyResponse.class)),
                examples = @ExampleObject(value = """
                    [
                      {
                        "id": 1,
                        "token": "oswl_SAM...KEY1",
                        "label": "CI Pipeline",
                        "active": true,
                        "lastUsedAt": "2026-05-01T10:23:00",
                        "createdAt": "2026-04-01T09:00:00"
                      }
                    ]
                    """))),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<List<ApiKeyResponse>> list(
        @Parameter(description = "Target project ID", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "Issue a new API key",
        description = """
            Generates a new `oswl_`-prefixed API key for the project.
            The **full token is returned only once** in this response — store it immediately.
            Subsequent list calls show a masked version.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Key issued — save the token now",
            content = @Content(schema = @Schema(implementation = ApiKeyIssueResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "id": 2,
                      "token": "oswl_aBcDeFgHiJkLmNoPqRsTuVwXyZ123456",
                      "label": "Local Dev",
                      "createdAt": "2026-05-02T23:00:00",
                      "message": "API key issued. Store this token securely — it won't be shown again."
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "Validation error — label is required", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<ApiKeyIssueResponse> issue(
        @Parameter(description = "Target project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @RequestBody(
            description = "Label (required) and optional expiry timestamp",
            required = true,
            content = @Content(
                schema = @Schema(implementation = ApiKeyIssueRequest.class),
                examples = @ExampleObject(value = """
                    {
                      "label": "CI Pipeline Key",
                      "expiresAt": "2027-01-01T00:00:00"
                    }
                    """)
            )
        )
        @Valid @org.springframework.web.bind.annotation.RequestBody ApiKeyIssueRequest request
    );

    @Operation(
        summary = "Revoke an API key",
        description = "Marks the key as inactive. Revoked keys are rejected by the CLI auth interceptor immediately."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Key revoked successfully", content = @Content),
        @ApiResponse(responseCode = "404", description = "Key not found or does not belong to the project", content = @Content)
    })
    ResponseEntity<Void> revoke(
        @Parameter(description = "Target project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Key ID to revoke", example = "2", required = true)
        @PathVariable Long keyId
    );
}
