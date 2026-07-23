package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.controller.SnapshotAdminController.SnapshotStatusResponse;
import com.salkcoding.oswl.service.AirgappedSnapshotService.SnapshotImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Offline Snapshot", description = "Air-gapped (offline snapshot) management. Requires the SYSTEM_ADMIN role.")
public interface SnapshotAdminControllerSpec {

    @Operation(summary = "Snapshot store status",
        description = "Returns the air-gapped flag and, per source (osv, depsdev-version, depsdev-advisory, epss, kev), the number of stored records and when they were last imported.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Store status",
            content = @Content(schema = @Schema(implementation = SnapshotStatusResponse.class)))
    })
    ResponseEntity<SnapshotStatusResponse> status();

    @Operation(summary = "Import an offline snapshot bundle",
        description = """
            Uploads a snapshot bundle (zip of JSONL files: `osv.jsonl`, `depsdev.jsonl`, `epss.jsonl`, `kev.jsonl`)
            and replaces the store contents for every source present in the bundle.
            With `oswl.airgapped.enabled=true`, OSV/deps.dev/EPSS/KEV lookups are then served
            from this store instead of live external APIs.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot imported — per-source record counts",
            content = @Content(schema = @Schema(implementation = SnapshotImportResult.class))),
        @ApiResponse(responseCode = "400", description = "Not a zip, or no snapshot data files in the bundle", content = @Content)
    })
    ResponseEntity<SnapshotImportResult> importBundle(
        @Parameter(description = "Snapshot bundle (.zip)", required = true)
        @RequestPart("file") MultipartFile file
    );

    @Operation(summary = "Export an offline snapshot bundle",
        description = """
            Builds a snapshot bundle from the vulnerability/threat-intel data this instance has
            already fetched (libraries + CVEs) and downloads it as a zip. Run this on an ONLINE
            instance, then import the bundle on the air-gapped one.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot bundle zip",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    })
    ResponseEntity<byte[]> exportBundle();
}
