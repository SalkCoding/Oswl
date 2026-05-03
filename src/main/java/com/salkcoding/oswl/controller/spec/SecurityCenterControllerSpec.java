package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Security Center", description = "Vulnerability overview page — lists all components with CVE counts aggregated from the latest completed scan.")
public interface SecurityCenterControllerSpec {

    @Operation(
        summary = "Security center overview",
        description = """
            Renders the Security Center page for a project.
            Aggregates CVE severity counts (Critical / High / Medium / Low) and license risk
            counts from the **latest COMPLETED** scan result.
            Each component row shows per-row CVE breakdown, patchability, and license status.
            Returns `404` when the project does not exist.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Security center page rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    String index(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        Model model
    );
}
