package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Scan Archiving", description = "Deletes old scans' component/CVE detail beyond a per-project retain count, keeping only an aggregate summary. Manually triggered — SYSTEM_ADMIN only.")
public interface ScanArchivingControllerSpec {

    @Operation(summary = "Archive a project's old scans",
        description = """
            Keeps the most recent `retainCount` completed scans in full detail; every older
            completed scan not already archived has its scan_components/dependency_paths rows
            deleted and an aggregate severity/license summary stamped onto the scan itself instead.
            Already-archived scans are skipped. Irreversible — there is no export-before-delete
            step yet.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Archiving result",
            content = @Content(schema = @Schema(implementation = ScanArchiveResult.class))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ScanArchiveResult archive(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Number of most-recent completed scans to keep in full detail; below 1 falls back to the instance default", example = "20")
        @RequestParam(required = false) Integer retainCount
    );
}
