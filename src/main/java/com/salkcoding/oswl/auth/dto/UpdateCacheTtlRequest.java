package com.salkcoding.oswl.auth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateCacheTtlRequest {
    @NotBlank
    private String cacheKey;

    @Min(1)
    private int ttlSeconds;
}
