package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Scan History", description = "Scan result history per project.")
public interface ScanHistoryControllerSpec {

    @Hidden
    String index(@PathVariable Long projectId, org.springframework.ui.Model model);

    @Operation(summary = "Delete a scan result",
        description = "Permanently removes a single scan result and all its component/CVE associations. Requires SCAN_HISTORY_DELETE permission.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Scan result deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Scan result not found for this project", content = @Content)
    })
    ResponseEntity<Void> deleteScan(
        @Parameter(description = "Project ID", example = "1", required = true) @PathVariable Long projectId,
        @Parameter(description = "Scan result ID to delete", example = "42", required = true) @PathVariable Long scanId
    );
}
