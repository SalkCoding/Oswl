package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.controller.spec.TeamControllerSpec;
import com.salkcoding.oswl.domain.entity.Organization;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.Team;
import com.salkcoding.oswl.domain.entity.TeamMember;
import com.salkcoding.oswl.domain.enums.TeamMemberRole;
import com.salkcoding.oswl.dto.TeamMemberDto;
import com.salkcoding.oswl.dto.TeamSummaryDto;
import com.salkcoding.oswl.repository.OrganizationRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.TeamMemberRepository;
import com.salkcoding.oswl.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Team management inside the single-organization hierarchy
 * (Organization → Team → Project, teams nested at most two levels).
 *
 * Team membership grants access to every project of the team (OR-ed with direct project
 * membership in {@link ProjectAccessService}). Managing the hierarchy itself requires the
 * global {@code TEAM_MANAGE} permission or SYSTEM_ADMIN, enforced at the controller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    /** Name of the built-in team that absorbs pre-hierarchy projects. Never deletable. */
    public static final String DEFAULT_TEAM_NAME = "Default";
    /** Name of the single organization row of this deployment. */
    public static final String DEFAULT_ORGANIZATION_NAME = "Default Organization";

    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // ── Bootstrap ────────────────────────────────────────────────────────

    /**
     * Guarantees the single organization and its "Default" team exist, and folds any
     * team-less project into the Default team. Idempotent — mirrors what the schema
     * migration does in SQL for Flyway-managed deployments, so ddl-auto deployments
     * (local H2) end up in the same state.
     *
     * @return the Default team
     */
    @Transactional
    public Team ensureDefaults() {
        Organization org = organizationRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> organizationRepository.save(
                        Organization.builder().name(DEFAULT_ORGANIZATION_NAME).build()));
        Team defaultTeam = teamRepository.findByName(DEFAULT_TEAM_NAME)
                .orElseGet(() -> teamRepository.save(Team.builder()
                        .organization(org)
                        .name(DEFAULT_TEAM_NAME)
                        .description("Projects created before teams were introduced.")
                        .build()));
        int folded = projectRepository.assignTeamWhereNull(defaultTeam);
        if (folded > 0) {
            log.info("[Team] Folded {} team-less project(s) into the Default team", folded);
        }
        return defaultTeam;
    }

    // ── Team CRUD ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamSummaryDto> listTeams() {
        return teamRepository.findAllByOrderByNameAsc().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional
    public TeamSummaryDto createTeam(String name, String description, Long parentTeamId) {
        String trimmed = requireName(name);
        if (teamRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A team with this name already exists.");
        }
        Organization org = organizationRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("Organization is not initialized."));
        Team parent = resolveParent(parentTeamId);
        Team saved = teamRepository.save(Team.builder()
                .organization(org)
                .name(trimmed)
                .description(trimToNull(description))
                .parent(parent)
                .build());
        auditLogService.log("TEAM.CREATE", "TEAM", saved.getId().toString(), saved.getName(),
                parent != null ? "parent=" + parent.getName() : null);
        log.info("[Team] Created id={} name='{}'", saved.getId(), saved.getName());
        return toSummary(saved);
    }

    @Transactional
    public TeamSummaryDto updateTeam(Long teamId, String name, String description, Long parentTeamId) {
        Team team = getTeam(teamId);
        String trimmed = requireName(name);
        if (!trimmed.equals(team.getName()) && teamRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A team with this name already exists.");
        }
        if (isDefaultTeam(team) && !DEFAULT_TEAM_NAME.equals(trimmed)) {
            throw new IllegalArgumentException("The Default team cannot be renamed.");
        }
        Team parent = resolveParent(parentTeamId);
        if (parent != null) {
            if (parent.getId().equals(team.getId())) {
                throw new IllegalArgumentException("A team cannot be its own parent.");
            }
            if (!teamRepository.findByParentId(team.getId()).isEmpty()) {
                // Team already has children — giving it a parent would create a third level.
                throw new IllegalArgumentException("A team with child teams cannot be nested under another team.");
            }
        }
        team.update(trimmed, trimToNull(description), parent);
        teamRepository.save(team);
        auditLogService.log("TEAM.UPDATE", "TEAM", team.getId().toString(), team.getName(),
                parent != null ? "parent=" + parent.getName() : "parent=null");
        log.info("[Team] Updated id={} name='{}'", team.getId(), team.getName());
        return toSummary(team);
    }

    /**
     * Deletes a team: its projects move to the Default team (so team members of Default
     * keep things visible and no project ends up orphaned), child teams are detached to
     * top level, and memberships go with the team.
     */
    @Transactional
    public void deleteTeam(Long teamId) {
        Team team = getTeam(teamId);
        if (isDefaultTeam(team)) {
            throw new IllegalArgumentException("The Default team cannot be deleted.");
        }
        Team defaultTeam = teamRepository.findByName(DEFAULT_TEAM_NAME)
                .orElseThrow(() -> new IllegalStateException("Default team is missing."));
        int moved = projectRepository.reassignTeam(team, defaultTeam);
        for (Team child : teamRepository.findByParentId(team.getId())) {
            child.clearParent();
            teamRepository.save(child);
        }
        teamMemberRepository.deleteByTeamId(team.getId());
        teamRepository.delete(team);
        auditLogService.log("TEAM.DELETE", "TEAM", teamId.toString(), team.getName(),
                "projectsMovedToDefault=" + moved);
        log.info("[Team] Deleted id={} name='{}' projectsMoved={}", teamId, team.getName(), moved);
    }

    // ── Membership ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamMemberDto> listMembers(Long teamId) {
        getTeam(teamId);
        List<TeamMember> members = teamMemberRepository.findByTeamIdOrderByCreatedAtAsc(teamId);
        Map<Long, User> usersById = userRepository.findAllById(
                        members.stream().map(TeamMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return members.stream()
                .map(m -> {
                    User user = usersById.get(m.getUserId());
                    return TeamMemberDto.builder()
                            .userId(m.getUserId())
                            .displayName(user != null ? user.getDisplayName() : "?")
                            .email(user != null ? user.getEmail() : "-")
                            .role(m.getRole().name())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public void addMember(Long teamId, Long userId, TeamMemberRole role) {
        Team team = getTeam(teamId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new IllegalArgumentException("The user is already a member of this team.");
        }
        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .userId(userId)
                .role(role != null ? role : TeamMemberRole.MEMBER)
                .build());
        auditLogService.log("TEAM.MEMBER_ADD", "TEAM", teamId.toString(), team.getName(),
                "user=" + user.getEmail() + " role=" + (role != null ? role : TeamMemberRole.MEMBER));
        log.info("[Team] Added userId={} to teamId={}", userId, teamId);
    }

    @Transactional
    public void updateMemberRole(Long teamId, Long userId, TeamMemberRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required.");
        }
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new IllegalArgumentException("The user is not a member of this team."));
        member.changeRole(role);
        teamMemberRepository.save(member);
        auditLogService.log("TEAM.MEMBER_ROLE", "TEAM", teamId.toString(), member.getTeam().getName(),
                "userId=" + userId + " role=" + role);
        log.info("[Team] Set role={} for userId={} in teamId={}", role, userId, teamId);
    }

    @Transactional
    public void removeMember(Long teamId, Long userId) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new IllegalArgumentException("The user is not a member of this team."));
        teamMemberRepository.delete(member);
        auditLogService.log("TEAM.MEMBER_REMOVE", "TEAM", teamId.toString(), member.getTeam().getName(),
                "userId=" + userId);
        log.info("[Team] Removed userId={} from teamId={}", userId, teamId);
    }

    // ── Project assignment ───────────────────────────────────────────────

    /** The built-in Default team, or null when the bootstrap has not run yet. */
    @Transactional(readOnly = true)
    public Team findDefaultTeam() {
        return teamRepository.findByName(DEFAULT_TEAM_NAME).orElse(null);
    }

    /** Active projects currently assigned to the team (team detail view). */
    @Transactional(readOnly = true)
    public List<TeamControllerSpec.TeamProjectDto> listTeamProjects(Long teamId) {
        getTeam(teamId);
        return projectRepository.findAllByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(teamId).stream()
                .map(p -> new TeamControllerSpec.TeamProjectDto(p.getId(), p.getName()))
                .collect(Collectors.toList());
    }

    /** Moves a project under a team. Both teams' member grants shift accordingly. */
    @Transactional
    public void assignProjectToTeam(Long projectId, Long teamId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Team team = getTeam(teamId);
        project.assignTeam(team);
        projectRepository.save(project);
        auditLogService.log("PROJECT.TEAM_ASSIGN", "PROJECT", projectId.toString(), project.getName(),
                "team=" + team.getName());
        log.info("[Team] Assigned projectId={} to teamId={}", projectId, teamId);
    }

    /** Removes a project from its team (project keeps only direct member grants). */
    @Transactional
    public void unassignProject(Long projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        String previousTeam = project.getTeam() != null ? project.getTeam().getName() : null;
        project.assignTeam(null);
        projectRepository.save(project);
        auditLogService.log("PROJECT.TEAM_ASSIGN", "PROJECT", projectId.toString(), project.getName(),
                "team=null (was " + previousTeam + ")");
        log.info("[Team] Unassigned projectId={} from team='{}'", projectId, previousTeam);
    }

    /** Enabled users for the member picker — id, display name and email only. */
    @Transactional(readOnly = true)
    public List<TeamMemberDto> userDirectory() {
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .map(u -> TeamMemberDto.builder()
                        .userId(u.getId())
                        .displayName(u.getDisplayName())
                        .email(u.getEmail())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Team getTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    private boolean isDefaultTeam(Team team) {
        return DEFAULT_TEAM_NAME.equals(team.getName());
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Team name is required.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Team name must not exceed 200 characters.");
        }
        return trimmed;
    }

    private Team resolveParent(Long parentTeamId) {
        if (parentTeamId == null) {
            return null;
        }
        Team parent = getTeam(parentTeamId);
        if (parent.getParent() != null) {
            // Two levels max — a nested team cannot have children of its own.
            throw new IllegalArgumentException("Teams can only be nested two levels deep.");
        }
        return parent;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    private TeamSummaryDto toSummary(Team team) {
        Team parent = team.getParent();
        return TeamSummaryDto.builder()
                .id(team.getId())
                .name(team.getName())
                .description(team.getDescription())
                .parentTeamId(parent != null ? parent.getId() : null)
                .parentTeamName(parent != null ? parent.getName() : null)
                .memberCount(teamMemberRepository.countByTeamId(team.getId()))
                .projectCount(projectRepository.countByTeamIdAndDeletedAtIsNull(team.getId()))
                .defaultTeam(isDefaultTeam(team))
                .build();
    }
}
