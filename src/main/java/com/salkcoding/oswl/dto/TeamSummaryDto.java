package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Team summary row for the team management page and team pickers")
@Getter
@Builder
@AllArgsConstructor
public class TeamSummaryDto {

    @Schema(description = "Team primary key", example = "1")
    private final Long id;

    @Schema(description = "Team name (unique within the organization)", example = "Payments")
    private final String name;

    @Schema(description = "Optional team description", example = "Payment platform services")
    private final String description;

    @Schema(description = "Parent team id — null for top-level teams (max two levels)", example = "3")
    private final Long parentTeamId;

    @Schema(description = "Parent team name — null for top-level teams", example = "Engineering")
    private final String parentTeamName;

    @Schema(description = "Number of team members", example = "5")
    private final long memberCount;

    @Schema(description = "Number of active (non-deleted) projects assigned to the team", example = "12")
    private final long projectCount;

    @Schema(description = "True for the built-in 'Default' team that cannot be deleted", example = "false")
    private final boolean defaultTeam;
}
