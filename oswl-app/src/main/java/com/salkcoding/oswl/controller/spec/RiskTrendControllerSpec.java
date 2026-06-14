package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Risk Trend", description = "Historical risk trend page — shows per-severity issue counts across the last 10 completed scans as Chart.js line graphs.")
public interface RiskTrendControllerSpec {

    @Operation(
        summary = "Risk trend overview",
        description = """
            Renders the risk trend page for a project.
            Fetches up to the **10 most recent COMPLETED** scan results and builds two line charts:
            - **Security Risk** — CVE count per severity (Critical / High / Medium / Low) per version
            - **License Risk** — component license-status count per severity per version

            Also exposes delta values (change from the previous scan) for the summary badges.
            Chart data is injected as `window.riskTrendData` for the Chart.js script.
            Returns `404` when the project does not exist.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Risk trend page rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    String index(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        Model model
    );
}
