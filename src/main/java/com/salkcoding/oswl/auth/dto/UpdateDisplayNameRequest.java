package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Update a user's display name")
@Data
public class UpdateDisplayNameRequest {
    @Schema(description = "New display name", example = "Alice Smith")
    private String displayName;
}
