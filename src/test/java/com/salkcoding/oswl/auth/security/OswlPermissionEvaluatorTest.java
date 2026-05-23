package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.enums.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OswlPermissionEvaluator unit tests")
class OswlPermissionEvaluatorTest {

    OswlPermissionEvaluator evaluator = new OswlPermissionEvaluator();

    private Authentication auth(String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken("user", null, authorities);
    }

    // -- Null/edge cases --

    @Test
    @DisplayName("Null auth: returns false")
    void nullAuth_returnsFalse() {
        assertThat(evaluator.hasPermission(null, null, "PROJECT_VIEW")).isFalse();
    }

    @Test
    @DisplayName("Null permission: returns false")
    void nullPermission_returnsFalse() {
        Authentication a = auth("ROLE_USER");
        assertThat(evaluator.hasPermission(a, null, (Object) null)).isFalse();
    }

    // -- SYSTEM_ADMIN always has all permissions --

    @Test
    @DisplayName("ROLE_SYSTEM_ADMIN: returns true for any permission")
    void systemAdmin_returnsTrue() {
        Authentication a = auth("ROLE_SYSTEM_ADMIN");
        assertThat(evaluator.hasPermission(a, null, "PROJECT_VIEW")).isTrue();
        assertThat(evaluator.hasPermission(a, null, "SETTINGS_AI_MANAGE")).isTrue();
    }

    // -- PERMISSION_ prefix matching --

    @Test
    @DisplayName("PERMISSION_PROJECT_VIEW authority: hasPermission('PROJECT_VIEW') returns true")
    void permissionAuthority_matchesPermissionName() {
        Authentication a = auth("PERMISSION_PROJECT_VIEW");
        assertThat(evaluator.hasPermission(a, null, "PROJECT_VIEW")).isTrue();
    }

    @Test
    @DisplayName("PERMISSION_PROJECT_VIEW authority: other permission returns false")
    void permissionAuthority_doesNotMatchOtherPermission() {
        Authentication a = auth("PERMISSION_PROJECT_VIEW");
        assertThat(evaluator.hasPermission(a, null, "SETTINGS_AI_MANAGE")).isFalse();
    }

    @Test
    @DisplayName("No matching authority: returns false")
    void noMatchingAuthority_returnsFalse() {
        Authentication a = auth("ROLE_USER");
        assertThat(evaluator.hasPermission(a, null, "PROJECT_VIEW")).isFalse();
    }

    // -- Overload with targetId/targetType --

    @Test
    @DisplayName("hasPermission(targetId,targetType): delegates to hasPermission(null,permission)")
    void targetIdOverload_delegatesToMain() {
        Authentication a = auth("ROLE_SYSTEM_ADMIN");
        assertThat(evaluator.hasPermission(a, 1L, "SOME_TYPE", "PROJECT_VIEW")).isTrue();
    }

    @Test
    @DisplayName("hasPermission(targetId,targetType): non-admin without permission returns false")
    void targetIdOverload_regularUser_returnsFalse() {
        Authentication a = auth("ROLE_USER");
        assertThat(evaluator.hasPermission(a, 1L, "SOME_TYPE", "PROJECT_VIEW")).isFalse();
    }

    // -- All permissions accessible by SYSTEM_ADMIN --

    @Test
    @DisplayName("SYSTEM_ADMIN passes all Permission enum values")
    void systemAdmin_passesAllEnumPermissions() {
        Authentication a = auth("ROLE_SYSTEM_ADMIN");
        for (Permission p : Permission.values()) {
            assertThat(evaluator.hasPermission(a, null, p.name())).isTrue();
        }
    }
}
