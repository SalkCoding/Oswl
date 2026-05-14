package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Projects", description = "Project management and Git/VCS helper endpoints used by the web UI.")
public interface ProjectControllerSpec {

    @Operation(
        summary = "Delete a project",
        description = "Permanently removes the project and all associated scan results, components, CVEs, and API keys."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Project deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<Void> deleteProject(
        @Parameter(description = "Project ID to delete", example = "1", required = true)
        @PathVariable Long projectId
    );

}
