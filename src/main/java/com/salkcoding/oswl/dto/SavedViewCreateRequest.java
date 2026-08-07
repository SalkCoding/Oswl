package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for saving a new Security Center filter view")
public record SavedViewCreateRequest(
        @Schema(example = "Critical, unreviewed only", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Schema(description = "Opaque client-authored JSON blob: filters/sortMode/searchQuery",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "filtersJson must not be blank")
        String filtersJson,

        @Schema(description = "Visible to everyone who can view the project (default: creator only)")
        boolean shared
) {}
