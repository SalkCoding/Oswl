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
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
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
            throw new IllegalArgumentException("이름은 1글자 이상이어야 합니다.");
        }
        if (displayName.trim().length() > 20) {
            throw new IllegalArgumentException("이름은 최대 20글자까지 입력할 수 있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setDisplayName(displayName.trim());
        log.debug("[User] Display name updated userId={}", userId);
        auditLogService.log("USER.UPDATE_NAME", "USER", userId.toString(), user.getEmail(), displayName.trim());
    }

    @Transactional
    public void updateUserRoles(Long userId, List<Long> templateIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("최고 관리자의 권한은 변경할 수 없습니다.");
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
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("최고 관리자의 상태는 변경할 수 없습니다.");
        }
        user.setEnabled(enabled);
        String action = enabled ? "USER.ACTIVATE" : "USER.DEACTIVATE";
        log.info("[User] {} userId={} email='{}'", enabled ? "활성화" : "비활성화", userId, user.getEmail());
        auditLogService.log(action, "USER", userId.toString(), user.getEmail(), user.getDisplayName());
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("최고 관리자는 삭제할 수 없습니다.");
        }
        String email = user.getEmail();
        String displayName = user.getDisplayName();
        user.getRoleTemplates().clear();
        userRepository.delete(user);
        log.info("[User] userId={} email='{}' 삭제", userId, email);
        auditLogService.log("USER.DELETE", "USER", userId.toString(), email, displayName);
    }

    public boolean hasAnyUser() {
        return userRepository.count() > 0;
    }

    /**
     * 주어진 이메일의 연속 로그인 실패 횟수를 1 증가한다.
     * 10회 실패 시 계정이 자동으로 비활성화된다.
     *
     * @return 새 실패 횟수, 또는 이메일을 찾지 못으면 0
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
                                "자동 잠김: 연속 " + count + "회 로그인 실패");
                        log.warn("[User] email='{}' 계정 자동 잠김, 연속 로그인 실패 {}회", email, count);
                    } else {
                        log.debug("[User] 로그인 실패 email='{}' count={}", email, count);
                    }
                    return count;
                })
                .orElse(0);
    }

    /** 로그인 성공 시 연속 로그인 실패 횟수를 0으로 완전 초기화한다. */
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
