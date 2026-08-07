package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "A saved Security Center filter/sort view")
public record SavedViewDto(
        Long id,
        String name,
        @Schema(description = "Opaque client-authored JSON blob: filters/sortMode/searchQuery, applied back verbatim")
        String filtersJson,
        boolean shared,
        Long createdByUserId,
        String createdByName,
        @Schema(description = "True when the current user created this view (only the creator may delete it)")
        boolean own,
        LocalDateTime createdAt
) {}
