package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "Lightweight role template reference")
@Data
@AllArgsConstructor
public class RoleTemplateRefDto {
    @Schema(description = "Role template primary key", example = "1")
    private Long id;
    @Schema(description = "Role template name", example = "Developer")
    private String name;
}
