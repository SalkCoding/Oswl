package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.controller.snapshot.SnapshotAdminController.SnapshotStatusResponse;
import com.salkcoding.oswl.dto.SnapshotImportFromPathRequest;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Offline Snapshot", description = "Air-gapped (offline snapshot) management. Requires the SYSTEM_ADMIN role.")
public interface SnapshotAdminControllerSpec {

    @Operation(summary = "Snapshot store status",
        description = "Returns the air-gapped flag and, per source (osv, depsdev-version, depsdev-advisory, epss, kev), " +
            "the number of stored records, when they were last imported, and (when the source bundle carried v2 " +
            "provenance) its bundleId/builtAt/sourceAsOf/origin — null for a source last imported from a v1 or meta-less bundle. " +
            "Also returns the oldest sourceAsOf across all sources and the configured staleness-warn/critical-day " +
            "thresholds, so the admin UI can render the definition-freshness badge without hardcoding them.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Store status",
            content = @Content(schema = @Schema(implementation = SnapshotStatusResponse.class)))
    })
    ResponseEntity<SnapshotStatusResponse> status();

    @Operation(summary = "Import an offline snapshot bundle",
        description = """
            Uploads a snapshot bundle (zip of JSONL files: `osv.jsonl`, `depsdev.jsonl`, `epss.jsonl`, `kev.jsonl`,
            plus a `meta.json` — see the Offline VDB docs for the v2 schema) and updates the store contents for
            every source present in the bundle. `mode=replace` (default when the bundle carries no `meta.json`
            `mode` either) clears each source before writing; `mode=merge` upserts by key and honors a `"_deleted":true`
            line as a delete. A v2 bundle's checksums are verified before any store mutation — a mismatch
            rejects the whole bundle and leaves the existing store untouched.
            With `oswl.airgapped.enabled=true`, OSV/deps.dev/EPSS/KEV lookups are then served
            from this store instead of live external APIs.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot imported — per-source record counts",
            content = @Content(schema = @Schema(implementation = SnapshotImportResult.class))),
        @ApiResponse(responseCode = "400", description = "Not a zip, no snapshot data files in the bundle, or a checksum mismatch", content = @Content)
    })
    ResponseEntity<SnapshotImportResult> importBundle(
        @Parameter(description = "Snapshot bundle (.zip)", required = true)
        @RequestPart("file") MultipartFile file,
        @Parameter(description = "replace | merge — overrides the bundle's own meta.json mode when given")
        @RequestParam(required = false) String mode
    );

    @Operation(summary = "Import an offline snapshot bundle already on the server's disk",
        description = """
            for large bundles where uploading through the browser is impractical. `path` is resolved under
            `oswl.airgapped.import-dir` (a server-side whitelist — the endpoint returns 400 if that setting is
            blank, or if `path` resolves outside it). Same mode/checksum/merge semantics as the multipart upload.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot imported — per-source record counts",
            content = @Content(schema = @Schema(implementation = SnapshotImportResult.class))),
        @ApiResponse(responseCode = "400", description = "Server-path import disabled, path outside the whitelist, not a zip, no data files, or a checksum mismatch", content = @Content)
    })
    ResponseEntity<SnapshotImportResult> importFromPath(@RequestBody SnapshotImportFromPathRequest request);

    @Operation(summary = "Export a wanted-list",
        description = """
            Streams one JSONL line per distinct (ecosystem, name, version) this instance has ever
            scanned — `{"ecosystem":"NPM","name":"left-pad","version":"1.3.0"}` — for handing to the
            `oswl-vdb build --wanted` CLI on an internet-connected machine, so it fetches only
            the components this instance actually uses instead of a full upstream mirror.
            Deliberately omits project names, repository URLs, and paths — only ecosystem/name/version
            leave the instance. Streamed directly from the database (no full in-memory list) so this
            stays cheap even for large instances.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "wanted-list.jsonl",
            content = @Content(mediaType = "application/x-ndjson", schema = @Schema(type = "string", format = "binary")))
    })
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> wantedList();

    @Operation(summary = "Export an offline snapshot bundle",
        description = """
            Builds a v2 snapshot bundle from the vulnerability/threat-intel data this instance has
            already fetched (libraries + CVEs) and downloads it as a zip. Run this on an ONLINE
            instance, then import the bundle on the air-gapped one. `meta.json`'s `origin` is set to
            `"derived-from-scan"` for every source — see the Offline VDB docs for the fidelity limits
            this implies compared to a bundle built directly from upstream data.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot bundle zip",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    })
    ResponseEntity<byte[]> exportBundle();
}
