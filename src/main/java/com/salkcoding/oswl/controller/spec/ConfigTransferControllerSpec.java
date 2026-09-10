package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.config.ConfigBundle;
import com.salkcoding.oswl.dto.config.ConfigImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Settings — Config Transfer", description = "Export/import a portable subset of instance config — role templates, license policy, AI settings minus secrets, cache TTLs. SYSTEM_ADMIN only.")
public interface ConfigTransferControllerSpec {

    @Operation(summary = "Export the config bundle",
        description = "Never includes any secret, API key, or password. redactedFields lists what must be re-entered by hand after import.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Config bundle",
            content = @Content(schema = @Schema(implementation = ConfigBundle.class))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content)
    })
    ConfigBundle export();

    @Operation(summary = "Import a config bundle",
        description = "dryRun=true (default) previews counts without writing anything. Set dryRun=false to apply.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Import result",
            content = @Content(schema = @Schema(implementation = ConfigImportResult.class))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content)
    })
    ConfigImportResult importBundle(
        @RequestBody ConfigBundle bundle,
        @Parameter(description = "Preview only — nothing is written when true", example = "true")
        @RequestParam(defaultValue = "true") boolean dryRun
    );
}
