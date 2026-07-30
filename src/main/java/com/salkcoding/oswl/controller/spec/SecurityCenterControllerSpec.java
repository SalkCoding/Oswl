package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.BulkStatusRequest;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

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
        @Parameter(description = "Specific scan ID to display; omit for latest")
        @RequestParam(required = false) Long scanId,
        Model model
    );

    @Hidden
    String print(
        @PathVariable Long projectId,
        @RequestParam(required = false) Long scanId,
        Model model
    );

    @Hidden
    String complianceReport(
        @PathVariable Long projectId,
        Model model
    );

    @Operation(
        summary = "Bulk-update component review status",
        description = """
            Marks the given components as reviewed/unreviewed and/or ignored/unignored in one call.
            `reviewed` and `ignored` are applied independently; `null` means no change.
            Requires SECURITY_CENTER_UPDATE_STATUS permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Statuses updated", content = @Content)
    })
    ResponseEntity<Void> bulkStatus(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @RequestBody BulkStatusRequest req
    );

    @Operation(
        summary = "Export security center data (CSV)",
        description = """
            Downloads the component/CVE overview of the given scan (latest when omitted) as a CSV file.
            Requires SECURITY_CENTER_EXPORT permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "CSV export", content = @Content(mediaType = "text/csv")),
        @ApiResponse(responseCode = "400", description = "Unsupported format — only `csv` is supported", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<byte[]> export(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Specific scan ID to export; omit for latest")
        @RequestParam(required = false) Long scanId,
        @Parameter(description = "Export format — only `csv` is supported", example = "csv")
        @RequestParam(defaultValue = "csv") String format
    );
}
