package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.TeamControllerSpec;
import com.salkcoding.oswl.domain.enums.TeamMemberRole;
import com.salkcoding.oswl.dto.TeamMemberDto;
import com.salkcoding.oswl.dto.TeamSummaryDto;
import com.salkcoding.oswl.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Team hierarchy management (Organization → Team → Project). The page and every
 * mutation require the global {@code TEAM_MANAGE} permission or SYSTEM_ADMIN;
 * project-level visibility granted by team membership is resolved separately in
 * {@code ProjectAccessService}.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'TEAM_MANAGE') or hasRole('SYSTEM_ADMIN')")
public class TeamController implements TeamControllerSpec {

    private final TeamService teamService;

    @GetMapping("/teams")
    public String index(Model model) {
        model.addAttribute("teams", teamService.listTeams());
        return "teams/index";
    }

    @GetMapping("/api/teams")
    @ResponseBody
    public List<TeamSummaryDto> listTeams() {
        return teamService.listTeams();
    }

    @PostMapping("/api/teams")
    @ResponseBody
    public TeamSummaryDto createTeam(@Valid @RequestBody TeamRequest request) {
        return teamService.createTeam(request.name(), request.description(), request.parentTeamId());
    }

    @PutMapping("/api/teams/{teamId}")
    @ResponseBody
    public TeamSummaryDto updateTeam(@PathVariable Long teamId, @Valid @RequestBody TeamRequest request) {
        return teamService.updateTeam(teamId, request.name(), request.description(), request.parentTeamId());
    }

    @DeleteMapping("/api/teams/{teamId}")
    @ResponseBody
    public ResponseEntity<Void> deleteTeam(@PathVariable Long teamId) {
        teamService.deleteTeam(teamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/teams/user-directory")
    @ResponseBody
    public List<TeamMemberDto> userDirectory() {
        return teamService.userDirectory();
    }

    @GetMapping("/api/teams/{teamId}/members")
    @ResponseBody
    public List<TeamMemberDto> listMembers(@PathVariable Long teamId) {
        return teamService.listMembers(teamId);
    }

    @PostMapping("/api/teams/{teamId}/members")
    @ResponseBody
    public ResponseEntity<Void> addMember(@PathVariable Long teamId,
                                          @Valid @RequestBody AddMemberRequest request) {
        teamService.addMember(teamId, request.userId(), parseRole(request.role()));
        return ResponseEntity.ok().build();
    }

    @PutMapping("/api/teams/{teamId}/members/{userId}")
    @ResponseBody
    public ResponseEntity<Void> updateMemberRole(@PathVariable Long teamId, @PathVariable Long userId,
                                                 @Valid @RequestBody UpdateMemberRoleRequest request) {
        teamService.updateMemberRole(teamId, userId, parseRole(request.role()));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/teams/{teamId}/members/{userId}")
    @ResponseBody
    public ResponseEntity<Void> removeMember(@PathVariable Long teamId, @PathVariable Long userId) {
        teamService.removeMember(teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/teams/{teamId}/projects")
    @ResponseBody
    public List<TeamProjectDto> listTeamProjects(@PathVariable Long teamId) {
        return teamService.listTeamProjects(teamId);
    }

    @PostMapping("/api/teams/{teamId}/projects")
    @ResponseBody
    public ResponseEntity<Void> assignProject(@PathVariable Long teamId,
                                              @Valid @RequestBody AssignProjectRequest request) {
        teamService.assignProjectToTeam(request.projectId(), teamId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/teams/{teamId}/projects/{projectId}")
    @ResponseBody
    public ResponseEntity<Void> unassignProject(@PathVariable Long teamId, @PathVariable Long projectId) {
        teamService.unassignProject(projectId);
        return ResponseEntity.noContent().build();
    }

    private static TeamMemberRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return TeamMemberRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown team role: " + role);
        }
    }
}
