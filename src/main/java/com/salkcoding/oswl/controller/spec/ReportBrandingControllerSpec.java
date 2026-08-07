package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.ReportBrandingResponse;
import com.salkcoding.oswl.dto.ReportBrandingUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import org.springframework.http.ResponseEntity;

@Tag(name = "Report Branding", description = "Logo, company name, header text, and cover page options applied to printable reports.")
public interface ReportBrandingControllerSpec {

    @Operation(summary = "Get report branding settings",
            description = "Returns the single-row branding settings applied to printable reports (compliance report today).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Branding settings",
                    content = @Content(schema = @Schema(implementation = ReportBrandingResponse.class)))
    })
    ResponseEntity<ReportBrandingResponse> getSettings();

    @Operation(summary = "Save report branding settings",
            description = "Upserts the single-row branding settings. A null logoDataUri leaves the stored logo unchanged; a blank one removes it.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Settings saved"),
            @ApiResponse(responseCode = "400", description = "Invalid logo image or validation error", content = @Content)
    })
    ResponseEntity<Void> saveSettings(
            @RequestBody(description = "Branding settings", required = true,
                    content = @Content(schema = @Schema(implementation = ReportBrandingUpdateRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody ReportBrandingUpdateRequest request
    );
}
