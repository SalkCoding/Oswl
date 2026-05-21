package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the forced password-change flow for admin-invited users.
 *
 * <p>Security invariants enforced here:
 * <ul>
 *   <li>Current (temporary) password must be verified before any change is applied.</li>
 *   <li>New password must not be identical to the current one.</li>
 *   <li>{@code mustChangePassword} is cleared atomically in the same transaction as the hash update.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangePasswordService {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    /**
     * Verifies {@code currentPassword}, encodes and stores {@code newPassword}, and clears the
     * {@code mustChangePassword} flag — all within a single transaction.
     *
     * @throws IllegalArgumentException with code {@code CURRENT_PASSWORD_WRONG} or
     *                                  {@code SAME_AS_CURRENT} on validation failure.
     * @throws IllegalStateException    if the user row cannot be found.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.warn("[ChangePassword] Wrong current password for userId={}", userId);
            throw new IllegalArgumentException("CURRENT_PASSWORD_WRONG");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            log.debug("[ChangePassword] Rejected: new password same as current for userId={}", userId);
            throw new IllegalArgumentException("SAME_AS_CURRENT");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // Dirty-check flush within the transaction — explicit save is not required
        // but kept for clarity.
        userRepository.save(user);

        auditLogService.log("AUTH.PASSWORD_CHANGE", "USER",
                userId.toString(), user.getEmail(), "Forced password change completed");

        log.info("[ChangePassword] Password updated for userId={}", userId);
    }
}
