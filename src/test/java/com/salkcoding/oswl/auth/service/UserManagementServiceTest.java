package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserManagementService 단위 테스트")
class UserManagementServiceTest {

    @Mock UserRepository         userRepository;
    @Mock RoleTemplateRepository roleTemplateRepository;
    @Mock PasswordEncoder        passwordEncoder;
    @Mock AuditLogService        auditLogService;

    @InjectMocks UserManagementService userManagementService;

    // ── createUser ───────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser: 정상적으로 유저가 생성되고 DTO가 반환된다")
    void createUser_success() {
        CreateUserRequest req = new CreateUserRequest();
        req.setEmail("New@Example.COM");
        req.setDisplayName("Alice");
        req.setTemporaryPassword("P@ssword1");

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("P@ssword1")).thenReturn("{bcrypt}encoded");

        User saved = User.builder()
                .id(10L)
                .email("new@example.com")
                .displayName("Alice")
                .passwordHash("{bcrypt}encoded")
                .enabled(true)
                .isSystemAdmin(false)
                .mustChangePassword(true)
                .roleTemplates(new HashSet<>())
                .build();
        when(userRepository.save(any(User.class))).thenReturn(saved);

        UserSummaryDto dto = userManagementService.createUser(req);

        assertThat(dto.getEmail()).isEqualTo("new@example.com");
        assertThat(dto.getDisplayName()).isEqualTo("Alice");
        assertThat(dto.getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("createUser: 이미 사용 중인 이메일이면 IllegalArgumentException")
    void createUser_duplicateEmail_throws() {
        CreateUserRequest req = new CreateUserRequest();
        req.setEmail("taken@example.com");
        req.setDisplayName("Bob");
        req.setTemporaryPassword("P@ssword1");

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userManagementService.createUser(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
    }

    // ── updateDisplayName ────────────────────────────────────────────────

    @Test
    @DisplayName("updateDisplayName: 정상 변경 성공")
    void updateDisplayName_success() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("Old")
                .enabled(true).isSystemAdmin(false).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userManagementService.updateDisplayName(1L, "New Name");

        assertThat(user.getDisplayName()).isEqualTo("New Name");
        verify(auditLogService).log(eq("USER.UPDATE_NAME"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateDisplayName: 빈 문자열이면 IllegalArgumentException")
    void updateDisplayName_blank_throws() {
        assertThatThrownBy(() -> userManagementService.updateDisplayName(1L, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 character");
    }

    @Test
    @DisplayName("updateDisplayName: 21자 초과이면 IllegalArgumentException")
    void updateDisplayName_tooLong_throws() {
        assertThatThrownBy(() -> userManagementService.updateDisplayName(1L, "A".repeat(21)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 20 characters");
    }

    @Test
    @DisplayName("updateDisplayName: 유저 없으면 IllegalArgumentException")
    void updateDisplayName_userNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.updateDisplayName(99L, "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // ── updateUserRoles ──────────────────────────────────────────────────

    @Test
    @DisplayName("updateUserRoles: SYSTEM_ADMIN 계정의 권한 변경 시 IllegalStateException")
    void updateUserRoles_systemAdmin_throws() {
        User admin = User.builder()
                .id(1L).email("admin@example.com").displayName("Admin")
                .isSystemAdmin(true).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userManagementService.updateUserRoles(1L, List.of(2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("System administrator");
    }

    @Test
    @DisplayName("updateUserRoles: 정상 역할 업데이트")
    void updateUserRoles_success() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        RoleTemplate role = RoleTemplate.builder().id(5L).name("Developer").build();
        when(roleTemplateRepository.findAllById(List.of(5L))).thenReturn(List.of(role));

        userManagementService.updateUserRoles(1L, List.of(5L));

        assertThat(user.getRoleTemplates()).containsExactly(role);
        verify(auditLogService).log(eq("USER.UPDATE_ROLES"), anyString(), anyString(), anyString(), anyString());
    }

    // ── setUserEnabled ───────────────────────────────────────────────────

    @Test
    @DisplayName("setUserEnabled: 일반 유저 비활성화 성공")
    void setUserEnabled_deactivate_success() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userManagementService.setUserEnabled(1L, false);

        assertThat(user.isEnabled()).isFalse();
        verify(auditLogService).log(eq("USER.DEACTIVATE"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("setUserEnabled: SYSTEM_ADMIN 상태 변경 시 IllegalStateException")
    void setUserEnabled_systemAdmin_throws() {
        User admin = User.builder()
                .id(1L).email("admin@example.com").displayName("Admin")
                .isSystemAdmin(true).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userManagementService.setUserEnabled(1L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("System administrator");
    }

    // ── deleteUser ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteUser: 일반 유저 삭제 성공")
    void deleteUser_success() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userManagementService.deleteUser(1L);

        verify(userRepository).delete(user);
        verify(auditLogService).log(eq("USER.DELETE"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("deleteUser: SYSTEM_ADMIN 삭제 시 IllegalStateException")
    void deleteUser_systemAdmin_throws() {
        User admin = User.builder()
                .id(1L).email("admin@example.com").displayName("Admin")
                .isSystemAdmin(true).enabled(true).passwordHash("x")
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userManagementService.deleteUser(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("deleteUser: 유저 없으면 IllegalArgumentException")
    void deleteUser_userNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.deleteUser(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── handleLoginFailure ───────────────────────────────────────────────

    @Test
    @DisplayName("handleLoginFailure: 실패 횟수 증가")
    void handleLoginFailure_incrementsCount() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .loginFailureCount(2)
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        int count = userManagementService.handleLoginFailure("U@EXAMPLE.COM");

        assertThat(count).isEqualTo(3);
        assertThat(user.getLoginFailureCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("handleLoginFailure: 이메일 없으면 0 반환")
    void handleLoginFailure_unknownEmail_returnsZero() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        int count = userManagementService.handleLoginFailure("nobody@example.com");

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("handleLoginFailure: 10회 실패 시 계정 비활성화")
    void handleLoginFailure_tenthFailure_disablesAccount() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .loginFailureCount(9)
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        int count = userManagementService.handleLoginFailure("u@example.com");

        assertThat(count).isEqualTo(10);
        assertThat(user.isEnabled()).isFalse();
        verify(auditLogService).logAnonymous(eq("u@example.com"), eq("USER.DEACTIVATE"),
                anyString(), anyString(), anyString(), anyString());
    }

    // ── resetLoginFailureCount ───────────────────────────────────────────

    @Test
    @DisplayName("resetLoginFailureCount: 실패 횟수가 0으로 리셋된다")
    void resetLoginFailureCount_resetsToZero() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .loginFailureCount(5)
                .roleTemplates(new HashSet<>()).build();
        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        userManagementService.resetLoginFailureCount("u@example.com");

        assertThat(user.getLoginFailureCount()).isEqualTo(0);
    }

    // ── hasAnyUser ───────────────────────────────────────────────────────

    @Test
    @DisplayName("hasAnyUser: 유저가 있으면 true")
    void hasAnyUser_usersExist_returnsTrue() {
        when(userRepository.count()).thenReturn(3L);

        assertThat(userManagementService.hasAnyUser()).isTrue();
    }

    @Test
    @DisplayName("hasAnyUser: 유저 없으면 false")
    void hasAnyUser_noUsers_returnsFalse() {
        when(userRepository.count()).thenReturn(0L);

        assertThat(userManagementService.hasAnyUser()).isFalse();
    }

    // ── findAllUsers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findAllUsers: 모든 사용자 DTO 목록 반환")
    void findAllUsers_returnsAllUsers() {
        User user = User.builder()
                .id(1L).email("u@example.com").displayName("U")
                .isSystemAdmin(false).enabled(true).passwordHash("x")
                .loginFailureCount(0).roleTemplates(new HashSet<>()).build();
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserSummaryDto> result = userManagementService.findAllUsers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEmail()).isEqualTo("u@example.com");
    }
}

