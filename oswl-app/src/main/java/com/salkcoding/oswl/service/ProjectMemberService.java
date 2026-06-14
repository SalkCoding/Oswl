package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.domain.entity.ProjectMember;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.dto.AddProjectMemberRequest;
import com.salkcoding.oswl.dto.ProjectMemberDto;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectAccessService projectAccessService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ProjectMemberDto> listMembers(Long projectId) {
        projectAccessService.assertCanViewProject(projectId);
        return projectMemberRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ProjectMemberDto addMember(Long projectId, AddProjectMemberRequest req) {
        assertCanManageMembers(projectId);
        User user = userRepository.findByEmail(req.getEmail().strip().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No user with email " + req.getEmail() + ". Invite them in Settings → Admin first."));

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw new ConflictException("User is already a member of this project.");
        }

        ProjectMemberRole role = req.getRole() != null ? req.getRole() : ProjectMemberRole.MEMBER;
        projectAccessService.ensureMember(projectId, user.getId(), role);
        return projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId()).stream()
                .findFirst()
                .map(this::toDto)
                .orElseThrow();
    }

    @Transactional
    public void removeMember(Long projectId, Long userId) {
        assertCanManageMembers(projectId);
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new IllegalArgumentException("User is not a member of this project.");
        }
        List<ProjectMember> admins = projectMemberRepository.findByProjectIdAndRole(
                projectId, ProjectMemberRole.ADMIN);
        boolean removingAdmin = admins.stream().anyMatch(a -> a.getUserId().equals(userId));
        if (removingAdmin && admins.size() <= 1) {
            throw new ConflictException("Cannot remove the last project admin.");
        }
        projectMemberRepository.deleteByProjectIdAndUserId(projectId, userId);
    }

    private void assertCanManageMembers(Long projectId) {
        var principal = projectAccessService.currentPrincipal();
        if (principal == null) {
            throw new ForbiddenException("You do not have permission to manage project members.");
        }
        if (principal.isSystemAdmin()) {
            return;
        }
        if (principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.PROJECT_MEMBER_MANAGE)) {
            projectAccessService.assertCanViewProject(projectId);
            boolean isAdmin = projectMemberRepository.findByProjectIdAndUserId(projectId, principal.getUserId())
                    .stream()
                    .anyMatch(m -> m.getRole() == ProjectMemberRole.ADMIN);
            if (isAdmin) {
                return;
            }
        }
        throw new ForbiddenException("You do not have permission to manage project members.");
    }

    private ProjectMemberDto toDto(ProjectMember member) {
        User user = userRepository.findById(member.getUserId()).orElse(null);
        return ProjectMemberDto.builder()
                .userId(member.getUserId())
                .email(user != null ? user.getEmail() : null)
                .displayName(user != null ? user.getDisplayName() : null)
                .role(member.getRole())
                .build();
    }
}
