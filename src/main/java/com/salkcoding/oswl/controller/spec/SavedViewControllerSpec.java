package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.SavedViewCreateRequest;
import com.salkcoding.oswl.dto.SavedViewDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Saved Views", description = "Named Security Center filter/sort combinations, personal or shared per project.")
public interface SavedViewControllerSpec {

    @Operation(summary = "List saved views",
            description = "Returns the caller's own saved views plus every view another user marked shared, for one project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of saved views",
                    content = @Content(schema = @Schema(implementation = SavedViewDto.class)))
    })
    List<SavedViewDto> list(
            @Parameter(description = "Project ID") @PathVariable Long projectId,
            @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Save a new filter view",
            description = "Stores the current Security Center filter/sort state under a name, for the caller to reapply or (if shared) for teammates to open with the same result.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved view created",
                    content = @Content(schema = @Schema(implementation = SavedViewDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
    })
    ResponseEntity<SavedViewDto> create(
            @Parameter(description = "Project ID") @PathVariable Long projectId,
            @Valid @RequestBody SavedViewCreateRequest request,
            @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Delete a saved view",
            description = "Only the view's creator may delete it, regardless of the shared flag.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Not the creator", content = @Content),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    ResponseEntity<Void> delete(
            @Parameter(description = "Project ID") @PathVariable Long projectId,
            @Parameter(description = "Saved view ID") @PathVariable Long viewId,
            @AuthenticationPrincipal OswlUserPrincipal principal
    );
}
