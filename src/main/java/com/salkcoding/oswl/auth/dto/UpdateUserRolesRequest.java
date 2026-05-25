package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "Replace a user's role template assignments")
@Data
public class UpdateUserRolesRequest {
    @Schema(description = "IDs of role templates to assign (empty list removes all roles)", example = "[1, 2]")
    private List<Long> templateIds;
}
