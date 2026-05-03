package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "License", description = "License compliance page — groups detected licenses by risk level and lists obligations derived from the latest scan.")
public interface LicenseControllerSpec {

    @Operation(
        summary = "License compliance overview",
        description = """
            Renders the license compliance page for a project.
            Licenses are grouped by name and assigned a risk level:
            - **CRITICAL** — at least one component with `VIOLATION` status
            - **HIGH** — at least one component with `WARN` status
            - **LOW** — all components are `OK`

            Also returns obligation counts (components with non-OK license status).
            Data is derived from the **latest COMPLETED** scan result.
            Returns `404` when the project does not exist.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "License page rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    String index(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        Model model
    );
}
