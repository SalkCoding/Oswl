package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.dto.UpdateCacheTtlRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Tag(name = "Settings — Cache", description = "Cache TTL management and manual cache clearing. Requires the SETTINGS_CACHE_MANAGE permission or the SYSTEM_ADMIN role.")
public interface CacheSettingsControllerSpec {

    @Operation(summary = "List cache entries",
        description = "Returns every cache with its TTL and last-cleared metadata.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cache list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CacheSettingDto.class)))),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_CACHE_MANAGE permission and not a SYSTEM_ADMIN", content = @Content)
    })
    List<CacheSettingDto> list();

    @Operation(summary = "Update a cache TTL")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "TTL updated", content = @Content),
        @ApiResponse(responseCode = "400", description = "Validation error — cacheKey is required and ttlSeconds must be at least 1", content = @Content),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_CACHE_MANAGE permission and not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Unknown cache key", content = @Content)
    })
    void update(@Valid @RequestBody UpdateCacheTtlRequest request);

    @Operation(summary = "Clear a cache",
        description = "Evicts all entries of the given cache immediately.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cache cleared", content = @Content),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_CACHE_MANAGE permission and not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "404", description = "Unknown cache key", content = @Content)
    })
    void clear(
        @Parameter(description = "Cache key to clear", example = "osv-vuln", required = true)
        @RequestParam String cacheKey,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );
}
