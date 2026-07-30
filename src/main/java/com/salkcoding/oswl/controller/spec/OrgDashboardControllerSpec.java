package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.ui.Model;

@Tag(name = "Org Dashboard", description = "Organization-wide portfolio dashboard — rolls per-project posture up to a single admin view.")
public interface OrgDashboardControllerSpec {

    @Operation(
        summary = "Organization portfolio dashboard",
        description = """
            Renders the cross-project admin dashboard.
            Aggregates CVE severity totals, CISA KEV unaddressed count, and license violations
            from each active project's **latest COMPLETED** scan, ranks projects worst-first,
            and charts the org-wide vulnerability trend over the last weeks.
            Restricted to `SYSTEM_ADMIN`.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Org dashboard page rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "403", description = "Not a system administrator", content = @Content)
    })
    String index(Model model);
}
