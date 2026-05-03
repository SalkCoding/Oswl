package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Component Detail", description = "Full detail view for a single open-source component — CVE list, license info, patchability, and AI summaries.")
public interface ComponentDetailControllerSpec {

    @Operation(
        summary = "Component detail page",
        description = """
            Renders the detail page (or HTMX fragment) for a specific component within a project.
            Loads the component from the **latest COMPLETED** scan that belongs to the given project.

            - When the request contains `HX-Request: true` the response is an HTMX partial
              (`component-detail/fragments/detail-content :: content`).
            - Otherwise the full page template is returned.

            Returns `404` when either the project or the component is not found.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Component detail rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Project or component not found", content = @Content)
    })
    String detail(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Component ID", example = "3", required = true)
        @PathVariable Long componentId,
        Model model,
        HttpServletRequest request
    );
}
