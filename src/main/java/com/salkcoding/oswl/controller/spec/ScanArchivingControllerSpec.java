package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.scan.ScanArchiveExportDto;
import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Tag(name = "Scan Archiving", description = "Deletes old scans' component/CVE detail beyond a per-project retain count, keeping only an aggregate summary. Manually triggered — SYSTEM_ADMIN only.")
public interface ScanArchivingControllerSpec {

    @Operation(summary = "Preview + export what a call to /archive-scans would delete",
        description = """
            Read-only. Returns the full component/CVE/dependency-path detail for every completed,
            not-yet-archived scan beyond `retainCount` — exactly the set of scans the archive
            endpoint would strip down to an aggregate summary. Call this first and save the
            response if you need a record of the detail before it's deleted; archiving itself
            is irreversible and this is the only way to keep a copy.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scans pending archival, in full detail",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ScanArchiveExportDto.class)))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    List<ScanArchiveExportDto> exportPendingArchive(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Number of most-recent completed scans to keep in full detail; below 1 falls back to the instance default", example = "20")
        @RequestParam(required = false) Integer retainCount
    );

    @Operation(summary = "Archive a project's old scans",
        description = """
            Keeps the most recent `retainCount` completed scans in full detail; every older
            completed scan not already archived has its scan_components/dependency_paths rows
            deleted and an aggregate severity/license summary stamped onto the scan itself instead.
            Already-archived scans are skipped. Irreversible — call the export endpoint above
            first if you want a copy of the detail before it's gone.
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
