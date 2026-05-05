package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@Tag(name = "External API Settings", description = "Configure third-party integrations: NVD API key for CVE enrichment and library cache policy.")
public interface ExternalSettingsControllerSpec {

    @Operation(
        summary = "Get current external API settings",
        description = """
            Returns the current state of external API integrations.
            - `nvdConfigured` — whether an NVD API key is stored
            - `permanentCache` — whether the library data cache never expires
            - `cacheTtlDays` — cache TTL in days when `permanentCache` is `false` (0 if permanent)
            
            The NVD API key is **never** returned in the response.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Settings returned",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = """
                    {
                      "nvdConfigured": true,
                      "permanentCache": false,
                      "cacheTtlDays": 30
                    }
                    """)))
    })
    ResponseEntity<Map<String, Object>> getSettings();

    @Operation(
        summary = "Update NVD API key",
        description = """
            Sets or clears the NVD (National Vulnerability Database) API key used for
            CVSS score and CWE enrichment.
            
            - Provide a non-empty `nvdApiKey` to enable NVD enrichment.
            - Provide an empty string or omit the field to remove the key and disable NVD enrichment.
            
            Removing the key disables NVD enrichment for future scans; previously cached data is retained.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "NVD key updated",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = {
                    @ExampleObject(name = "enabled",  value = "{ \"nvdConfigured\": true }"),
                    @ExampleObject(name = "disabled", value = "{ \"nvdConfigured\": false }")
                }))
    })
    @RequestBody(
        description = "NVD API key payload",
        required = true,
        content = @Content(
            schema = @Schema(type = "object"),
            examples = @ExampleObject(value = "{ \"nvdApiKey\": \"your-nvd-api-key\" }")
        )
    )
    ResponseEntity<Map<String, Object>> updateNvd(
        @org.springframework.web.bind.annotation.RequestBody Map<String, String> body
    );

    @Operation(
        summary = "Update library cache policy",
        description = """
            Controls how long fetched library data (licenses, CVEs from deps.dev / OSV) is cached.
            
            - `permanentCache: true` — cache entries never expire; fetched once and reused indefinitely.
            - `permanentCache: false, cacheTtlDays: N` — cache entries expire after N days.
            
            Setting a shorter TTL increases external API calls but keeps vulnerability data fresher.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cache policy updated",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = {
                    @ExampleObject(name = "permanent", value = """
                        { "permanentCache": true, "cacheTtlDays": 0 }
                        """),
                    @ExampleObject(name = "ttl-30-days", value = """
                        { "permanentCache": false, "cacheTtlDays": 30 }
                        """)
                }))
    })
    @RequestBody(
        description = "Cache policy payload",
        required = true,
        content = @Content(
            schema = @Schema(type = "object"),
            examples = @ExampleObject(value = """
                { "permanentCache": false, "cacheTtlDays": 30 }
                """)
        )
    )
    ResponseEntity<Map<String, Object>> updateCache(
        @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body
    );
}
