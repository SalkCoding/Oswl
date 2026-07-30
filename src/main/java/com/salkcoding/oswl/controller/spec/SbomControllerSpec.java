package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.service.SbomImportService.SbomImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "SBOM", description = "CycloneDX SBOM export and import.")
public interface SbomControllerSpec {

    @Operation(
        summary = "Export CycloneDX SBOM",
        description = """
            Generates a CycloneDX 1.6 SBOM for the project's most recent completed scan.
            Components carry purl, version, and license expression; known vulnerabilities are
            embedded in the `vulnerabilities` section with CVSS ratings and fix recommendations.
            Returned as a downloadable file. Requires PROJECT_VIEW permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SBOM file (JSON: `application/vnd.cyclonedx+json`, XML: `application/xml`)"),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Project has no completed scan yet", content = @Content)
    })
    ResponseEntity<byte[]> exportSbom(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Output format — `json` (default) or `xml`", example = "json")
        @RequestParam(defaultValue = "json") String format
    );

    @Operation(
        summary = "Export CycloneDX VEX",
        description = """
            Generates a CycloneDX 1.6 VEX document for the project's most recent completed scan:
            the vulnerability list with an `analysis` block derived from the live triage state.
            Mapping: deferral `false-positive` → `false_positive`; `wont-fix` (accepted risk) →
            `exploitable` + response `will_not_fix`; `temporary` (fix planned) → `exploitable` +
            response `update`; `legal-review`/`other` and reviewed components → `in_triage`;
            untouched vulnerabilities carry no analysis block. `affects.ref` values match the
            purl bom-refs of the SBOM export so the two documents correlate.
            Triage changes are reflected immediately on the next export. Requires PROJECT_VIEW permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "VEX file (JSON: `application/vnd.cyclonedx+json`, XML: `application/xml`)"),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Project has no completed scan yet", content = @Content)
    })
    ResponseEntity<byte[]> exportVex(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId,
        @Parameter(description = "Output format — `json` (default) or `xml`", example = "json")
        @RequestParam(defaultValue = "json") String format
    );

    @Operation(
        summary = "Export SARIF 2.1.0 (GitHub Code Scanning)",
        description = """
            Exports the project's most recent completed scan as a SARIF 2.1.0 document compatible
            with `github/codeql-action/upload-sarif`. Each distinct CVE/GHSA is a SARIF rule with a
            `security-severity` property (drives GitHub's alert severity); each affected component is
            a result. Deferred/ignored components are marked as suppressed. Requires PROJECT_VIEW.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SARIF file (`application/sarif+json`)"),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Project has no completed scan yet", content = @Content)
    })
    ResponseEntity<byte[]> exportSarif(
        @Parameter(description = "Project ID", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "Import a CycloneDX SBOM as a scan",
        description = """
            Uploads a CycloneDX SBOM (JSON or XML) and ingests its components through the same
            pipeline as Quick Import: purls are mapped to the supported ecosystems
            (maven, npm, pypi, golang, cargo, nuget, gem), a scan is created, and asynchronous
            vulnerability/license enrichment starts immediately.
            Provide `projectId` to import into an existing project, or `projectName` to create a
            new one (falls back to the SBOM metadata component name). Components without a
            usable purl are skipped and counted in the response. Requires PROJECT_CREATE permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SBOM imported — scan created and enrichment started",
            content = @Content(schema = @Schema(implementation = SbomImportResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid SBOM file or no importable components", content = @Content),
        @ApiResponse(responseCode = "404", description = "projectId given but project not found", content = @Content)
    })
    ResponseEntity<SbomImportResult> importSbom(
        @Parameter(description = "CycloneDX SBOM file (.json or .xml)", required = true)
        @RequestPart("file") MultipartFile file,
        @Parameter(description = "Existing project ID to import into (optional)")
        @RequestParam(required = false) Long projectId,
        @Parameter(description = "Name for a new project (optional — defaults to SBOM metadata name)")
        @RequestParam(required = false) String projectName,
        @Parameter(description = "Scan version label (optional — defaults to SBOM metadata version)")
        @RequestParam(required = false) String version
    );
}
