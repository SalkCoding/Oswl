package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.scim.ScimPatchRequest;
import com.salkcoding.oswl.dto.scim.ScimUser;
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

@Tag(name = "SCIM 2.0 — Users", description = "User provisioning endpoints for SAML/SCIM identity providers.")
@SecurityRequirement(name = "ScimBearerAuth")
public interface ScimUserControllerSpec {

    @Operation(summary = "Get a user by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found",
            content = @Content(schema = @Schema(implementation = ScimUser.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    ResponseEntity<?> getUser(
        @Parameter(description = "SCIM user ID", example = "2", required = true) Long id
    );

    @Operation(summary = "List users", description = "Returns a SCIM list response. Supports a simple `userName eq \"email\"` filter.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User list returned", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content)
    })
    ResponseEntity<?> listUsers(
        @Parameter(description = "Optional filter, e.g. userName eq \"alice@example.com\"") String filter,
        @Parameter(description = "1-based start index") int startIndex,
        @Parameter(description = "Page size") int count
    );

    @Operation(summary = "Create a user")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = ScimUser.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content)
    })
    ResponseEntity<?> createUser(
        @RequestBody(description = "SCIM user resource", required = true,
            content = @Content(schema = @Schema(implementation = ScimUser.class)))
        ScimUser request
    );

    @Operation(summary = "Replace a user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User replaced",
            content = @Content(schema = @Schema(implementation = ScimUser.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    ResponseEntity<?> updateUser(Long id, ScimUser request);

    @Operation(summary = "Patch a user", description = "Supports replacing the `active` status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User patched",
            content = @Content(schema = @Schema(implementation = ScimUser.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    ResponseEntity<?> patchUser(Long id, ScimPatchRequest request);

    @Operation(summary = "Deactivate a user", description = "Sets active=false. OsWL never deletes users via SCIM.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "User deactivated", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid SCIM bearer token", content = @Content),
        @ApiResponse(responseCode = "403", description = "System administrator cannot be deactivated", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    ResponseEntity<Void> deleteUser(Long id);
}
