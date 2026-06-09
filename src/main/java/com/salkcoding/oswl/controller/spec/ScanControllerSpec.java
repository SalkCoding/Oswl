package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.api.PingResponse;
import com.salkcoding.oswl.dto.api.ScanParseResponse;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.api.ScanStatusResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "CLI Scan", description = "Endpoints consumed by the OSWL CLI agent. All requests must carry `Authorization: Bearer oswl_<token>`.")
public interface ScanControllerSpec {

    @Operation(
        summary = "Health-check / token validation",
        description = "Used by `oswl auth` to verify the API key and retrieve the associated project ID. Returns `200 OK` when the key is valid.",
        security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token is valid",
            content = @Content(schema = @Schema(implementation = PingResponse.class),
                examples = @ExampleObject(value = """
                    { "status": "ok", "projectId": 1 }
                    """))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content)
    })
    ResponseEntity<PingResponse> ping(
        @Parameter(hidden = true) HttpServletRequest request
    );

    @Operation(
        summary = "Parse manifest archive (CLI)",
        description = """
            Accepts a zip of project manifest files (relative paths preserved) and returns
            parsed components using the same server-side parser as Quick Import.

            **Example CLI flow:**
            ```
            oswl scan ./my-project   # CLI zips manifests, calls this endpoint, then POST /api/scan
            ```
            """,
        security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Manifests parsed",
            content = @Content(schema = @Schema(implementation = ScanParseResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid or empty archive", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content)
    })
    ResponseEntity<ScanParseResponse> parseManifests(
        @Parameter(description = "Zip archive of manifest files", required = true)
        @RequestPart("archive") MultipartFile archive
    ) throws Exception;

    @Operation(
        summary = "Manifest collection rules (CLI sync)",
        description = "Returns skip dirs and file patterns used by the CLI when packaging manifests. "
                + "Same content as `/scripts/manifest-rules.json`.",
        security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Rules JSON")
    ResponseEntity<com.salkcoding.oswl.service.manifest.ManifestCollectRules.RulesJson> manifestRules();

    @Operation(
        summary = "Submit a scan result",
        description = """
            Receives a full dependency scan payload from the CLI agent and persists it.
            On success the server runs AI analysis in the background (if configured).

            **Example CLI call:**
            ```
            oswl scan ./my-project
            ```
            """,
        security = @SecurityRequirement(name = "BearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scan accepted and stored",
            content = @Content(schema = @Schema(implementation = ScanResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "scanId": 42,
                      "projectId": 1,
                      "version": "1.3.0",
                      "status": "COMPLETED",
                      "message": "Scan received successfully"
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "Invalid payload", content = @Content),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content)
    })
    ResponseEntity<ScanResponse> receiveScan(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Full scan payload produced by the CLI agent",
            required = true,
            content = @Content(
                schema = @Schema(implementation = ScanPayload.class),
                examples = @ExampleObject(name = "minimal", value = """
                    {
                      "version": "1.3.0",
                      "components": [
                        {
                          "name": "jackson-databind",
                          "version": "2.15.0",
                          "patchability": "patchable",
                          "licenseStatus": "PERMITTED",
                          "licenseName": "Apache-2.0",
                          "cves": [
                            {
                              "cveId": "CVE-2024-12345",
                              "severity": "HIGH",
                              "cvssScore": 7.5,
                              "type": "Deserialization",
                              "discoveredOn": "2024-01-10",
                              "affects": "Direct dep.",
                              "fixVersion": "2.16.0"
                            }
                          ]
                        }
                      ]
                    }
                    """)
            )
        )
        @Valid @RequestBody ScanPayload payload,
        @Parameter(hidden = true) HttpServletRequest request
    ) throws Exception;

    @Operation(
        summary = "Poll scan status",
        description = """
            Lightweight UI polling endpoint — returns the current status and component count
            for a given scan result. Used by the Security Center banner to auto-refresh when a
            scan is in-progress.

            **This endpoint does NOT require an API key.** It is authenticated by the user's
            web session context (project access is implicitly scoped by the URL).
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scan status returned",
            content = @Content(schema = @Schema(implementation = ScanStatusResponse.class),
                examples = @ExampleObject(value = """
                    { "scanId": 42, "status": "ANALYZING", "componentCount": 128 }
                    """))),
        @ApiResponse(responseCode = "404", description = "Scan result not found", content = @Content)
    })
    ResponseEntity<ScanStatusResponse> scanStatus(
        @Parameter(description = "Scan result ID", example = "42", required = true)
        @PathVariable Long scanId
    );
}
