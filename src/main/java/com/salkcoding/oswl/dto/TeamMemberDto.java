package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Team member row — user identity plus the team-scoped role")
@Getter
@Builder
@AllArgsConstructor
public class TeamMemberDto {

    @Schema(description = "User primary key", example = "7")
    private final Long userId;

    @Schema(description = "User display name", example = "Jane Doe")
    private final String displayName;

    @Schema(description = "User email", example = "jane@example.com")
    private final String email;

    @Schema(description = "Team role — LEAD or MEMBER", example = "MEMBER")
    private final String role;
}
