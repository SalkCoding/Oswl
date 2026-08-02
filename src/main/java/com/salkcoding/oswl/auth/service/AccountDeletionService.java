package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account deletion. Removes the {@link User} row and personal linkage
 * (memberships, VCS tokens) while leaving audit logs and import/scan history intact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    /**
     * Deletes the authenticated user's account after password confirmation.
     *
     * @param authenticatedUserId must come from the security principal — never from the client body
     */
    @Transactional
    public void deleteOwnAccount(Long authenticatedUserId, String currentPassword) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        if (user.isSystemAdmin()) {
            throw new IllegalStateException(
                    "The primary system administrator account cannot be deleted. Transfer admin duties first.");
        }

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required to delete your account.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        String email = user.getEmail();
        String displayName = user.getDisplayName();
        Long userId = user.getId();

        auditLogService.log("USER.SELF_DELETE", "USER", userId.toString(), email, displayName);

        projectMemberRepository.deleteByUserId(userId);
        teamMemberRepository.deleteByUserId(userId);
        vcsConnectionRepository.deleteByUser_Id(userId);
        user.getRoleTemplates().clear();
        userRepository.delete(user);

        log.info("[User] Self-deleted userId={} email='{}'", userId, email);
    }
}
