package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.scim.ScimGroup;
import com.salkcoding.oswl.dto.scim.ScimPatchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "SCIM 2.0 — Groups", description = "Group provisioning endpoints. Groups map to Team or RoleTemplate based on oswl.scim.group-mapping.")
@SecurityRequirement(name = "ScimBearerAuth")
public interface ScimGroupControllerSpec {

    @Operation(summary = "Get a group by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group found",
            content = @Content(schema = @Schema(implementation = ScimGroup.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Group not found", content = @Content)
    })
    ResponseEntity<?> getGroup(
        @Parameter(description = "SCIM group ID", example = "3", required = true) Long id
    );

    @Operation(summary = "List groups")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group list returned", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content)
    })
    ResponseEntity<?> listGroups();

    @Operation(summary = "Create a group")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Group created",
            content = @Content(schema = @Schema(implementation = ScimGroup.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content)
    })
    ResponseEntity<?> createGroup(
        @RequestBody(description = "SCIM group resource", required = true,
            content = @Content(schema = @Schema(implementation = ScimGroup.class)))
        ScimGroup request
    );

    @Operation(summary = "Replace a group")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group replaced",
            content = @Content(schema = @Schema(implementation = ScimGroup.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Group not found", content = @Content)
    })
    ResponseEntity<?> updateGroup(Long id, ScimGroup request);

    @Operation(summary = "Patch a group", description = "Supports replacing the members list.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group patched",
            content = @Content(schema = @Schema(implementation = ScimGroup.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Group not found", content = @Content)
    })
    ResponseEntity<?> patchGroup(Long id, ScimPatchRequest request);

    @Operation(summary = "Delete a group")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Group deleted", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Built-in role template cannot be deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Group not found", content = @Content)
    })
    ResponseEntity<Void> deleteGroup(Long id);
}
