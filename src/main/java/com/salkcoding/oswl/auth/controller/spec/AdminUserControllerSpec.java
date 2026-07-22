package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.UpdateDisplayNameRequest;
import com.salkcoding.oswl.auth.dto.UpdateUserRolesRequest;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Admin — Users", description = "User account administration. All endpoints require the SYSTEM_ADMIN role.")
public interface AdminUserControllerSpec {

    @Operation(summary = "List users",
        description = "Returns all user accounts with their role template assignments.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserSummaryDto.class)))),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    List<UserSummaryDto> list();

    @Operation(summary = "Create a user",
        description = "Creates a new user account with a temporary password; the user must change it on first login.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User created",
            content = @Content(schema = @Schema(implementation = UserSummaryDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation error — display name, a valid email, and a temporary password (min 8 characters) are required", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    UserSummaryDto create(@Valid @RequestBody CreateUserRequest request);

    @Operation(summary = "Replace a user's role templates",
        description = "Replaces the user's role template assignments with the given set. An empty list removes all roles.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Roles updated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    void updateRoles(
        @Parameter(description = "User ID", example = "2", required = true) @PathVariable Long id,
        @RequestBody UpdateUserRolesRequest request
    );

    @Operation(summary = "Update a user's display name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Display name updated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    void updateDisplayName(
        @Parameter(description = "User ID", example = "2", required = true) @PathVariable Long id,
        @RequestBody UpdateDisplayNameRequest request
    );

    @Operation(summary = "Activate a user",
        description = "Enables the account so the user can sign in.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User activated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    void activate(
        @Parameter(description = "User ID", example = "2", required = true) @PathVariable Long id
    );

    @Operation(summary = "Deactivate a user",
        description = "Disables the account so the user can no longer sign in.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User deactivated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    void deactivate(
        @Parameter(description = "User ID", example = "2", required = true) @PathVariable Long id
    );

    @Operation(summary = "Delete a user",
        description = "Permanently deletes the user account.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User deleted", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    void delete(
        @Parameter(description = "User ID", example = "2", required = true) @PathVariable Long id
    );
}
