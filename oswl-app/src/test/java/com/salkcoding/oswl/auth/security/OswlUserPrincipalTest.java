package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.enums.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@DisplayName("OswlUserPrincipal 단위 테스트")
class OswlUserPrincipalTest {

    private OswlUserPrincipal createPrincipal(boolean systemAdmin, Set<Permission> permissions) {
        return new OswlUserPrincipal(
                1L,
                "user@example.com",
                "{noop}password",
                "Test User",
                systemAdmin,
                true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Set.of(10L),
                permissions,
                false
        );
    }

    @Test
    @DisplayName("getUserId: 생성자에 전달한 userId를 반환한다")
    void getUserId_returnsInjectedId() {
        OswlUserPrincipal p = createPrincipal(false, Set.of());

        assertThat(p.getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDisplayName: 생성자에 전달한 displayName을 반환한다")
    void getDisplayName_returnsInjectedName() {
        OswlUserPrincipal p = createPrincipal(false, Set.of());

        assertThat(p.getDisplayName()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("isSystemAdmin: systemAdmin=true이면 true를 반환한다")
    void isSystemAdmin_trueWhenFlagIsTrue() {
        OswlUserPrincipal p = createPrincipal(true, Set.of());

        assertThat(p.isSystemAdmin()).isTrue();
    }

    @Test
    @DisplayName("isSystemAdmin: systemAdmin=false이면 false를 반환한다")
    void isSystemAdmin_falseWhenFlagIsFalse() {
        OswlUserPrincipal p = createPrincipal(false, Set.of());

        assertThat(p.isSystemAdmin()).isFalse();
    }

    @Test
    @DisplayName("hasPermission: 시스템 어드민이면 모든 권한에 true를 반환한다")
    void hasPermission_systemAdmin_alwaysTrue() {
        OswlUserPrincipal p = createPrincipal(true, Set.of());

        assertThat(p.hasPermission(Permission.PROJECT_VIEW)).isTrue();
        assertThat(p.hasPermission(Permission.SCAN_SUBMIT)).isTrue();
    }

    @Test
    @DisplayName("hasPermission: permissions에 포함된 권한이면 true를 반환한다")
    void hasPermission_containedPermission_returnsTrue() {
        OswlUserPrincipal p = createPrincipal(false, Set.of(Permission.PROJECT_VIEW));

        assertThat(p.hasPermission(Permission.PROJECT_VIEW)).isTrue();
    }

    @Test
    @DisplayName("hasPermission: permissions에 없는 권한이면 false를 반환한다")
    void hasPermission_missingPermission_returnsFalse() {
        OswlUserPrincipal p = createPrincipal(false, Set.of(Permission.PROJECT_VIEW));

        assertThat(p.hasPermission(Permission.SCAN_SUBMIT)).isFalse();
    }

    @Test
    @DisplayName("isMustChangePassword: 생성자에 전달한 값을 반환한다")
    void isMustChangePassword_reflectsConstructorFlag() {
        OswlUserPrincipal p = new OswlUserPrincipal(
                2L, "u@e.com", "{noop}pw", "Name", false, true,
                List.of(), Set.of(), Set.of(), true);

        assertThat(p.isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("getRoleTemplateIds: 생성자에 전달한 ID Set을 반환한다")
    void getRoleTemplateIds_returnsInjectedSet() {
        OswlUserPrincipal p = new OswlUserPrincipal(
                3L, "u@e.com", "{noop}pw", "Name", false, true,
                List.of(), Set.of(5L, 6L), Set.of(), false);

        assertThat(p.getRoleTemplateIds()).containsExactlyInAnyOrder(5L, 6L);
    }
}
