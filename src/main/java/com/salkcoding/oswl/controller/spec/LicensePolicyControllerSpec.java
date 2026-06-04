package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.LicensePolicyEntryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "License Policy", description = "Instance-wide SPDX license policy used during enrichment and AI license triage.")
public interface LicensePolicyControllerSpec {

    @Operation(summary = "List license policy entries")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All SPDX entries with policy status",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = LicensePolicyEntryDto.class))))
    })
    List<LicensePolicyEntryDto> list();

    @Operation(summary = "Update policy status for an SPDX ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated entry",
                    content = @Content(schema = @Schema(implementation = LicensePolicyEntryDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid status", content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown SPDX ID", content = @Content)
    })
    ResponseEntity<LicensePolicyEntryDto> update(
            @Parameter(description = "SPDX license identifier", example = "GPL-3.0-only", required = true)
            @PathVariable String spdxId,
            @Valid @RequestBody UpdateRequest request
    );

    @Schema(description = "License policy status update")
    record UpdateRequest(
            @Schema(description = "Policy classification", example = "RESTRICTED",
                    implementation = LicenseStatus.class)
            LicenseStatus status
    ) {}
}
