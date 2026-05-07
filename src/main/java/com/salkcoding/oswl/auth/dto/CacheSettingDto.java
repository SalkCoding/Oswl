package com.salkcoding.oswl.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class CacheSettingDto {
    private String cacheKey;
    private int ttlSeconds;
    private int ttlHours;
    private LocalDateTime lastClearedAt;
    private String lastClearedByName;
}
