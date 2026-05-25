package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Cache entry configuration and status")
@Data
@Builder
@AllArgsConstructor
public class CacheSettingDto {
    @Schema(description = "Cache key identifier", example = "nvd-cve")
    private String cacheKey;
    @Schema(description = "Time-to-live in seconds", example = "86400")
    private long ttlSeconds;
    @Schema(description = "Time-to-live in hours (derived from ttlSeconds)", example = "24")
    private long ttlHours;
    @Schema(description = "Last manual clear timestamp (ISO-8601, null if never cleared)", example = "2026-05-01T04:00:00")
    private LocalDateTime lastClearedAt;
    @Schema(description = "Display name of the user who last cleared this cache", example = "Alice")
    private String lastClearedByName;
}
