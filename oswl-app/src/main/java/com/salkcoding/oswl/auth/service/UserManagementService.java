package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.RoleTemplateRefDto;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.aop.Auditable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleTemplateRepository roleTemplateRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<UserSummaryDto> findAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @Auditable(action = "USER.CREATE", targetType = "USER",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.email",
               detailExpr = "#result.displayName")
    public UserSummaryDto createUser(CreateUserRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email is already in use.");
        }
        Set<RoleTemplate> templates = new HashSet<>();
        if (request.getTemplateIds() != null && !request.getTemplateIds().isEmpty()) {
            templates.addAll(roleTemplateRepository.findAllById(request.getTemplateIds()));
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getTemporaryPassword()))
                .displayName(request.getDisplayName().trim())
                .isSystemAdmin(false)
                .enabled(true)
                .mustChangePassword(true)
                .roleTemplates(templates)
                .build();
        UserSummaryDto created = toDto(userRepository.save(user));
        log.info("[User] Created userId={} email='{}'", created.getId(), created.getEmail());
        return created;
    }

    @Transactional
    public void updateDisplayName(Long userId, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("Name must contain at least 1 character.");
        }
        if (displayName.trim().length() > 20) {
            throw new IllegalArgumentException("Name can be at most 20 characters long.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        user.setDisplayName(displayName.trim());
        log.debug("[User] Display name updated userId={}", userId);
        auditLogService.log("USER.UPDATE_NAME", "USER", userId.toString(), user.getEmail(), displayName.trim());
    }

    @Transactional
    public void updateUserRoles(Long userId, List<Long> templateIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("System administrator permissions cannot be changed.");
        }
        Set<RoleTemplate> templates = new HashSet<>();
        if (templateIds != null && !templateIds.isEmpty()) {
            templates.addAll(roleTemplateRepository.findAllById(templateIds));
        }
        user.getRoleTemplates().clear();
        user.getRoleTemplates().addAll(templates);
        String templateNames = templates.stream().map(RoleTemplate::getName).collect(Collectors.joining(", "));
        auditLogService.log("USER.UPDATE_ROLES", "USER", userId.toString(), user.getEmail(), templateNames);
    }

    @Transactional
    public void setUserEnabled(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("System administrator status cannot be changed.");
        }
        user.setEnabled(enabled);
        String action = enabled ? "USER.ACTIVATE" : "USER.DEACTIVATE";
        log.info("[User] {} userId={} email='{}'", enabled ? "Activated" : "Deactivated", userId, user.getEmail());
        auditLogService.log(action, "USER", userId.toString(), user.getEmail(), user.getDisplayName());
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("System administrator cannot be deleted.");
        }
        String email = user.getEmail();
        String displayName = user.getDisplayName();
        user.getRoleTemplates().clear();
        userRepository.delete(user);
        log.info("[User] Deleted userId={} email='{}'", userId, email);
        auditLogService.log("USER.DELETE", "USER", userId.toString(), email, displayName);
    }

    public boolean hasAnyUser() {
        return userRepository.count() > 0;
    }

    /**
     * Increments the consecutive login failure count for the given email by 1.
     * The account is automatically disabled after 10 failures.
     *
     * @return the new failure count, or 0 if the email cannot be found
     */
    @Transactional
    public int handleLoginFailure(String email) {
        return userRepository.findByEmail(email.toLowerCase())
                .map(user -> {
                    int count = user.getLoginFailureCount() + 1;
                    user.setLoginFailureCount(count);
                    if (count >= 10 && user.isEnabled()) {
                        user.setEnabled(false);
                        auditLogService.logAnonymous(email, "USER.DEACTIVATE", "USER",
                                user.getId().toString(), email,
                                "Auto-locked after " + count + " consecutive login failures");
                        log.warn("[User] email='{}' account auto-locked after {} consecutive login failures", email, count);
                    } else {
                        log.debug("[User] Login failure email='{}' count={}", email, count);
                    }
                    return count;
                })
                .orElse(0);
    }

    /** Fully resets the consecutive login failure count to 0 on successful login. */
    @Transactional
    public void resetLoginFailureCount(String email) {
        userRepository.findByEmail(email.toLowerCase())
                .ifPresent(u -> u.setLoginFailureCount(0));
    }

    private UserSummaryDto toDto(User user) {
        List<RoleTemplateRefDto> refs = user.getRoleTemplates().stream()
                .map(rt -> new RoleTemplateRefDto(rt.getId(), rt.getName()))
                .collect(Collectors.toList());
        return UserSummaryDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .systemAdmin(user.isSystemAdmin())
                .enabled(user.isEnabled())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .roleTemplates(refs)
                .build();
    }
}
