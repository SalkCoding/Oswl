package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "Update the TTL for a cache entry")
@Data
public class UpdateCacheTtlRequest {
    @Schema(description = "Cache key to update", example = "osv-vuln")
    @NotBlank
    private String cacheKey;

    @Schema(description = "New time-to-live in seconds (minimum 1)", example = "86400")
    @Min(1)
    private long ttlSeconds;
}
