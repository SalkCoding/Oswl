package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl 단위 테스트")
class UserDetailsServiceImplTest {

    @Mock UserRepository userRepository;

    @InjectMocks UserDetailsServiceImpl service;

    private User buildUser(boolean systemAdmin, boolean enabled, boolean mustChange, Set<RoleTemplate> roles) {
        return User.builder()
                .id(1L)
                .email("test@test.com")
                .passwordHash("$2a$hash")
                .displayName("Test User")
                .isSystemAdmin(systemAdmin)
                .enabled(enabled)
                .mustChangePassword(mustChange)
                .roleTemplates(roles)
                .build();
    }

    @Test
    @DisplayName("loadUserByUsername: 존재하지 않는 이메일 → UsernameNotFoundException")
    void loadUserByUsername_notFound_throwsException() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("unknown@test.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("unknown@test.com");
    }

    @Test
    @DisplayName("loadUserByUsername: 일반 사용자 → OswlUserPrincipal, systemAdmin=false")
    void loadUserByUsername_normalUser_returnsCorrectPrincipal() {
        User user = buildUser(false, true, false, new HashSet<>());
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertThat(details).isInstanceOf(OswlUserPrincipal.class);
        OswlUserPrincipal p = (OswlUserPrincipal) details;
        assertThat(p.getUserId()).isEqualTo(1L);
        assertThat(p.getUsername()).isEqualTo("test@test.com");
        assertThat(p.isSystemAdmin()).isFalse();
        assertThat(p.isMustChangePassword()).isFalse();
        assertThat(p.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("loadUserByUsername: systemAdmin 사용자 → ROLE_SYSTEM_ADMIN 권한 부여")
    void loadUserByUsername_systemAdmin_hasSystemAdminRole() {
        User user = buildUser(true, true, false, new HashSet<>());
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("admin@test.com");

        assertThat(details.getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("ROLE_SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("loadUserByUsername: 역할 템플릿 권한 → PERMISSION_ prefix 권한 추가")
    void loadUserByUsername_withRoleTemplate_hasPermissionAuthority() {
        RoleTemplate rt = RoleTemplate.builder()
                .id(10L)
                .name("Viewer")
                .permissions(EnumSet.of(Permission.PROJECT_VIEW))
                .build();
        User user = buildUser(false, true, false, new HashSet<>(Set.of(rt)));
        when(userRepository.findByEmail("viewer@test.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("viewer@test.com");

        assertThat(details.getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("PERMISSION_PROJECT_VIEW");
    }

    @Test
    @DisplayName("loadUserByUsername: mustChangePassword=true → principal에 반영")
    void loadUserByUsername_mustChangePassword_setInPrincipal() {
        User user = buildUser(false, true, true, new HashSet<>());
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        OswlUserPrincipal p = (OswlUserPrincipal) service.loadUserByUsername("test@test.com");

        assertThat(p.isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("loadUserByUsername: 비활성화 사용자 → enabled=false")
    void loadUserByUsername_disabledUser_notEnabled() {
        User user = buildUser(false, false, false, new HashSet<>());
        when(userRepository.findByEmail("disabled@test.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("disabled@test.com");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("loadUserByUsername: 역할 템플릿 id → roleTemplateIds에 포함")
    void loadUserByUsername_withRoleTemplate_includesRoleTemplateId() {
        RoleTemplate rt = RoleTemplate.builder()
                .id(20L)
                .name("Admin")
                .permissions(EnumSet.noneOf(Permission.class))
                .build();
        User user = buildUser(false, true, false, new HashSet<>(Set.of(rt)));
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        OswlUserPrincipal p = (OswlUserPrincipal) service.loadUserByUsername("test@test.com");

        assertThat(p.getRoleTemplateIds()).contains(20L);
    }
}
