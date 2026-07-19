package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Settings — VCS Connections", description = "Personal VCS access token management used by Quick Import and the VCS helpers. Requires the SETTINGS_VCS_MANAGE permission or the SYSTEM_ADMIN role.")
public interface VcsConnectionControllerSpec {

    @Operation(summary = "List VCS connections",
        description = "Returns the current user's active VCS connections. Access tokens are never returned.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "VCS connection list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = VcsConnectionDto.class)))),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_VCS_MANAGE permission and not a SYSTEM_ADMIN", content = @Content)
    })
    List<VcsConnectionDto> list(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Add a VCS connection",
        description = """
            Validates the access token against the provider and stores it encrypted.
            `serverUrl` is required for self-hosted GitLab/Bitbucket; `vcsUsername` is required for Bitbucket.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Connection added",
            content = @Content(schema = @Schema(implementation = VcsConnectionDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation error or token rejected by the provider", content = @Content),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_VCS_MANAGE permission and not a SYSTEM_ADMIN", content = @Content)
    })
    VcsConnectionDto add(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Valid @RequestBody AddVcsConnectionRequest request
    );

    @Operation(summary = "Remove a VCS connection",
        description = "Deletes one of the current user's VCS connections.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Connection removed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_VCS_MANAGE permission and not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Connection not found or owned by another user", content = @Content)
    })
    void remove(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(description = "VCS connection ID", example = "1", required = true) @PathVariable Long id
    );
}
