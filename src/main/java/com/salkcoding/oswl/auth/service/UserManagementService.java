package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.RoleTemplateRefDto;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleTemplateRepository roleTemplateRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserSummaryDto> findAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
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
                .roleTemplates(templates)
                .build();
        return toDto(userRepository.save(user));
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
    }

    @Transactional
    public void setUserEnabled(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("최고 관리자의 상태는 변경할 수 없습니다.");
        }
        user.setEnabled(enabled);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("최고 관리자는 삭제할 수 없습니다.");
        }
        user.getRoleTemplates().clear();
        userRepository.delete(user);
    }

    public boolean hasAnyUser() {
        return userRepository.count() > 0;
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
                .roleTemplates(refs)
                .build();
    }
}
