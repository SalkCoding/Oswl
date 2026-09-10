package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.TeamMemberDto;
import com.salkcoding.oswl.dto.TeamSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Teams", description = """
        Organization → Team → Project hierarchy. Team membership grants access to every project
        of the team (OR-ed with direct project membership). Teams nest at most two levels.
        All endpoints require the TEAM_MANAGE permission or SYSTEM_ADMIN.
        """)
public interface TeamControllerSpec {

    @Schema(description = "Create/update team request")
    record TeamRequest(
            @Schema(description = "Team name (unique within the organization)", example = "Payments")
            @NotBlank(message = "Team name is required")
            @Size(max = 200, message = "Team name must not exceed 200 characters")
            String name,

            @Schema(description = "Optional team description", example = "Payment platform services")
            @Size(max = 1000, message = "Description must not exceed 1000 characters")
            String description,

            @Schema(description = "Parent team id — null for a top-level team (max two levels)", example = "3")
            Long parentTeamId
    ) {}

    @Schema(description = "Add team member request")
    record AddMemberRequest(
            @Schema(description = "User id to add", example = "7")
            @NotNull(message = "User is required")
            Long userId,

            @Schema(description = "Team role — LEAD or MEMBER (defaults to MEMBER)", example = "MEMBER")
            String role
    ) {}

    @Schema(description = "Change team member role request")
    record UpdateMemberRoleRequest(
            @Schema(description = "New team role — LEAD or MEMBER", example = "LEAD")
            @NotBlank(message = "Role is required")
            String role
    ) {}

    @Schema(description = "Assign project to team request")
    record AssignProjectRequest(
            @Schema(description = "Project id to move into the team", example = "12")
            @NotNull(message = "Project is required")
            Long projectId
    ) {}

    @Schema(description = "Project row inside a team detail view")
    record TeamProjectDto(
            @Schema(description = "Project primary key", example = "12") Long id,
            @Schema(description = "Project name", example = "acme/api-service") String name
    ) {}

    @Operation(summary = "Team management page",
            description = "Renders the team hierarchy management UI (teams, members, project assignment).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team management page rendered",
                    content = @Content(mediaType = "text/html")),
            @ApiResponse(responseCode = "403", description = "Missing TEAM_MANAGE permission", content = @Content)
    })
    String index(Model model);

    @Operation(summary = "List teams", description = "All teams with member/project counts, ordered by name.")
    @ApiResponse(responseCode = "200", description = "Team list")
    List<TeamSummaryDto> listTeams();

    @Operation(summary = "Create team")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team created"),
            @ApiResponse(responseCode = "400", description = "Validation failure or nesting deeper than two levels",
                    content = @Content)
    })
    TeamSummaryDto createTeam(@RequestBody TeamRequest request);

    @Operation(summary = "Update team", description = "Rename, re-describe or re-parent a team. The Default team cannot be renamed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team updated"),
            @ApiResponse(responseCode = "404", description = "Team not found", content = @Content)
    })
    TeamSummaryDto updateTeam(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                              @RequestBody TeamRequest request);

    @Operation(summary = "Delete team",
            description = "Projects move to the Default team, child teams become top-level, memberships are removed.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Team deleted"),
            @ApiResponse(responseCode = "404", description = "Team not found", content = @Content)
    })
    ResponseEntity<Void> deleteTeam(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId);

    @Operation(summary = "User directory", description = "Enabled users (id, display name, email) for the member picker.")
    @ApiResponse(responseCode = "200", description = "Enabled user list")
    List<TeamMemberDto> userDirectory();

    @Operation(summary = "List team members")
    @ApiResponse(responseCode = "200", description = "Member list")
    List<TeamMemberDto> listMembers(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId);

    @Operation(summary = "Add team member", description = "Grants the user access to every project of the team.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Member added"),
            @ApiResponse(responseCode = "404", description = "Team or user not found", content = @Content)
    })
    ResponseEntity<Void> addMember(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                                   @RequestBody AddMemberRequest request);

    @Operation(summary = "Change team member role")
    @ApiResponse(responseCode = "200", description = "Role updated")
    ResponseEntity<Void> updateMemberRole(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                                          @Parameter(description = "User ID", required = true) @PathVariable Long userId,
                                          @RequestBody UpdateMemberRoleRequest request);

    @Operation(summary = "Remove team member",
            description = "Revokes the team grant; direct project memberships of the user are unaffected.")
    @ApiResponse(responseCode = "204", description = "Member removed")
    ResponseEntity<Void> removeMember(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                                      @Parameter(description = "User ID", required = true) @PathVariable Long userId);

    @Operation(summary = "List team projects", description = "Active projects currently assigned to the team.")
    @ApiResponse(responseCode = "200", description = "Project list")
    List<TeamProjectDto> listTeamProjects(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId);

    @Operation(summary = "Assign project to team",
            description = "Moves the project under the team — team members immediately gain access.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project assigned"),
            @ApiResponse(responseCode = "404", description = "Team or project not found", content = @Content)
    })
    ResponseEntity<Void> assignProject(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                                       @RequestBody AssignProjectRequest request);

    @Operation(summary = "Remove project from team",
            description = "Detaches the project from its team; direct project memberships keep working.")
    @ApiResponse(responseCode = "204", description = "Project unassigned")
    ResponseEntity<Void> unassignProject(@Parameter(description = "Team ID", required = true) @PathVariable Long teamId,
                                         @Parameter(description = "Project ID", required = true) @PathVariable Long projectId);
}
