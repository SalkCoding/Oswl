package com.salkcoding.oswl.service.project;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.project.ProjectMember;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.project.ProjectMemberRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.org.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single entry point for project-scoped access control.
 *
 * Resolution order (first match wins, all grants are OR-ed):
 * <ol>
 *   <li>SYSTEM_ADMIN — bypasses every membership check.</li>
 *   <li>Team role — the user is a {@code team_members} row of the team that owns the project.</li>
 *   <li>Project membership — the user has a direct {@code project_members} row.</li>
 * </ol>
 * Team grants and direct project memberships are additive, so pre-hierarchy
 * {@code project_members} rows keep granting exactly the same access as before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional(readOnly = true)
    public boolean canViewProject(Long projectId) {
        OswlUserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        if (principal.isSystemAdmin()) {
            return true;
        }
        return hasProjectAccess(projectId, principal.getUserId());
    }

    /** Team grant OR direct project membership for the given user. */
    private boolean hasProjectAccess(Long projectId, Long userId) {
        if (teamMemberRepository.existsTeamGrantForProject(projectId, userId)) {
            return true;
        }
        return projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
    }

    @Transactional(readOnly = true)
    public void assertCanViewProject(Long projectId) {
        if (!canViewProject(projectId)) {
            log.warn("[ProjectACL] Denied view projectId={} userId={}",
                    projectId, currentUserIdOrNull());
            throw new ForbiddenException("You do not have access to this project.");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanSubmitScan(Long projectId, Long userId) {
        if (userId == null) {
            throw new ForbiddenException("You do not have access to submit scans for this project.");
        }
        OswlUserPrincipal current = currentPrincipal();
        if (current != null && current.isSystemAdmin()) {
            return;
        }
        if (!hasProjectAccess(projectId, userId)) {
            log.warn("[ProjectACL] Denied scan submit projectId={} userId={}", projectId, userId);
            throw new ForbiddenException("You do not have access to submit scans for this project.");
        }
    }

    @Transactional(readOnly = true)
    public List<Long> accessibleProjectIds() {
        OswlUserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return List.of();
        }
        if (principal.isSystemAdmin()) {
            return projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                    .map(Project::getId)
                    .toList();
        }
        // Direct project memberships ∪ projects owned by any team the user belongs to.
        Set<Long> ids = new LinkedHashSet<>(
                projectMemberRepository.findProjectIdsByUserId(principal.getUserId()));
        ids.addAll(projectRepository.findProjectIdsByTeamMembership(principal.getUserId()));
        return new ArrayList<>(ids);
    }

    @Transactional
    public void ensureMember(Long projectId, Long userId, ProjectMemberRole role) {
        if (userId == null || projectId == null) {
            return;
        }
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            return;
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        projectMemberRepository.save(ProjectMember.builder()
                .project(project)
                .userId(userId)
                .role(role != null ? role : ProjectMemberRole.MEMBER)
                .build());
        log.info("[ProjectACL] Added userId={} to projectId={} role={}", userId, projectId, role);
    }

    @Transactional
    public void ensureCreatorMemberIfAbsent(Project project) {
        if (project == null || project.getCreatedByUserId() == null) {
            return;
        }
        if (projectMemberRepository.countByProjectId(project.getId()) == 0) {
            ensureMember(project.getId(), project.getCreatedByUserId(), ProjectMemberRole.ADMIN);
        }
    }

    private OswlUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OswlUserPrincipal p) {
            return p;
        }
        return null;
    }

    public Long currentUserIdOrNull() {
        OswlUserPrincipal p = currentPrincipal();
        return p != null ? p.getUserId() : null;
    }
}
