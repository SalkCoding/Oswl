package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "License", description = "License compliance page — groups detected licenses by risk level, lists deployment-aware obligations, detects cross-license conflicts and exports NOTICE/SPDX artifacts.")
public interface LicenseControllerSpec {

    @Operation(
        summary = "License compliance overview",
        description = """
            Renders the license compliance page for a project.

            Obligations and conflicts are filtered by the supplied **deployment context**:
            * `deployment` — `SAAS` | `BINARY` | `LIBRARY` | `EMBEDDED`
            * `modified`  — `true` when the team patched upstream sources (triggers STATE_CHANGES)
            * `linking`   — `STATIC` | `DYNAMIC` (relevant for LGPL ALLOW_RELINKING)

            Returns `404` when the project does not exist.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "License page rendered", content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    String index(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Specific scan ID to display; omit for latest", required = false)
        @RequestParam(required = false) Long scanId,
        @Parameter(description = "Deployment model — SAAS / BINARY / LIBRARY / EMBEDDED", example = "BINARY")
        @RequestParam(required = false, defaultValue = "BINARY") String deployment,
        @Parameter(description = "True when upstream sources are modified", example = "false")
        @RequestParam(required = false, defaultValue = "false") boolean modified,
        @Parameter(description = "Linking mode — STATIC or DYNAMIC", example = "DYNAMIC")
        @RequestParam(required = false, defaultValue = "DYNAMIC") String linking,
        Model model
    );

    @Operation(
        summary = "Download THIRD-PARTY NOTICE file",
        description = "Plain-text NOTICE.txt aggregating every detected component grouped by license — suitable for inclusion in distribution archives."
    )
    @ApiResponses(@ApiResponse(responseCode = "200", description = "NOTICE file", content = @Content(mediaType = "text/plain")))
    ResponseEntity<byte[]> exportNotice(
        @Parameter(description = "Project ID", example = "1", required = true) @PathVariable Long projectId,
        @Parameter(description = "Optional scan ID; latest when omitted", required = false) @RequestParam(required = false) Long scanId
    );

    @Operation(
        summary = "Download SPDX 2.3 SBOM (tag-value)",
        description = "Software Bill of Materials in SPDX 2.3 tag-value format — accepted by compliance tooling such as FOSSology, ScanCode and OpenChain."
    )
    @ApiResponses(@ApiResponse(responseCode = "200", description = "SPDX document", content = @Content(mediaType = "text/plain")))
    ResponseEntity<byte[]> exportSpdx(
        @Parameter(description = "Project ID", example = "1", required = true) @PathVariable Long projectId,
        @Parameter(description = "Optional scan ID; latest when omitted", required = false) @RequestParam(required = false) Long scanId
    );
}
