package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectMember;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Project-scoped access control. System administrators bypass membership checks.
 * All other users must be listed in {@code project_members} for the target project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public boolean canViewProject(Long projectId) {
        OswlUserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return false;
        }
        if (principal.isSystemAdmin()) {
            return true;
        }
        return projectMemberRepository.existsByProjectIdAndUserId(projectId, principal.getUserId());
    }

    @Transactional(readOnly = true)
    public void assertCanViewProject(Long projectId) {
        if (!canViewProject(projectId)) {
            log.warn("[ProjectACL] Denied view projectId={} userId={}",
                    projectId, currentUserIdOrNull());
            throw new ForbiddenException("You do not have access to this project.");
        }
    }

    /**
     * Ensures the scan's project matches the given scan id and the caller may view it.
     */
    @Transactional(readOnly = true)
    public void assertCanViewScan(Long scanId, Long projectId) {
        if (projectId == null) {
            throw new ForbiddenException("You do not have access to this scan.");
        }
        assertCanViewProject(projectId);
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
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
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
        return projectMemberRepository.findProjectIdsByUserId(principal.getUserId());
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

    public OswlUserPrincipal currentPrincipal() {
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

    /** Filters the given ids to those the current user may access (admins receive the input list). */
    @Transactional(readOnly = true)
    public Set<Long> filterAccessibleProjectIds(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Collections.emptySet();
        }
        OswlUserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return Collections.emptySet();
        }
        if (principal.isSystemAdmin()) {
            return Set.copyOf(projectIds);
        }
        return projectMemberRepository.findAccessibleProjectIds(projectIds, principal.getUserId())
                .stream()
                .collect(Collectors.toSet());
    }
}
