package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
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
import java.util.Locale;
import java.util.Map;

@Tag(name = "Admin — Role Templates", description = "Role template (permission bundle) administration. All endpoints require the SYSTEM_ADMIN role.")
public interface AdminRoleTemplateControllerSpec {

    @Operation(summary = "List role templates",
        description = "Returns all role templates with their assigned permissions and user counts.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role template list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = RoleTemplateDto.class)))),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    List<RoleTemplateDto> list();

    @Operation(summary = "List all available permissions",
        description = "Returns every permission code with a localized description. Used to populate the role template editor.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Permission list — objects with `code` and `description`"),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    List<Map<String, String>> allPermissions(
        @Parameter(hidden = true) Locale locale
    );

    @Operation(summary = "Create a role template")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role template created",
            content = @Content(schema = @Schema(implementation = RoleTemplateDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation error — name is required (max 100 characters)", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    RoleTemplateDto create(@Valid @RequestBody RoleTemplateRequest request);

    @Operation(summary = "Update a role template")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role template updated",
            content = @Content(schema = @Schema(implementation = RoleTemplateDto.class))),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Role template not found", content = @Content)
    })
    RoleTemplateDto update(
        @Parameter(description = "Role template ID", example = "1", required = true) @PathVariable Long id,
        @RequestBody RoleTemplateRequest request
    );

    @Operation(summary = "Delete a role template",
        description = "Deletes a role template. Built-in system templates cannot be deleted.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role template deleted", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Role template not found", content = @Content)
    })
    void delete(
        @Parameter(description = "Role template ID", example = "1", required = true) @PathVariable Long id
    );
}
