package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.dto.DeferralRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

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

    @Operation(summary = "Regenerate AI summary for a CVE",
            description = "Calls the configured AI provider to produce a fresh structured triage for one CVE on this component.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated CVE with AI fields",
                    content = @Content(schema = @Schema(implementation = CveDto.class))),
            @ApiResponse(responseCode = "400", description = "AI unavailable (not configured, cap reached, provider error, etc.)", content = @Content)
    })
    ResponseEntity<CveDto> regenerateCveAi(
            @PathVariable Long projectId,
            @PathVariable Long componentId,
            @Parameter(description = "CVE database row ID") @PathVariable Long cveDbId
    );

    @Operation(summary = "Defer remediation for a component")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deferral recorded", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content)
    })
    ResponseEntity<Void> defer(
            @PathVariable Long projectId,
            @PathVariable Long componentId,
            @RequestBody DeferralRequest req
    );

    @Operation(summary = "Create a pull request with dependency fix",
            description = "Opens a PR on the linked VCS repository using the user's GitHub token when available.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PR created",
                    content = @Content(schema = @Schema(example = "{\"url\": \"https://github.com/org/repo/pull/1\"}"))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "502", description = "VCS upstream error", content = @Content)
    })
    ResponseEntity<Map<String, Object>> createPr(
            @PathVariable Long projectId,
            @PathVariable Long componentId,
            @RequestBody CreatePrRequest req,
            HttpSession session,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );
}
