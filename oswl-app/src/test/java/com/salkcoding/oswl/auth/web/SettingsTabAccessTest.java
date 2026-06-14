package com.salkcoding.oswl.auth.web;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@DisplayName("SettingsTabAccess 단위 테스트")
class SettingsTabAccessTest {

    private OswlUserPrincipal adminPrincipal() {
        return new OswlUserPrincipal(
                1L, "admin@test.com", "hash", "Admin",
                true, true, List.of(), Set.of(), Set.of(), false);
    }

    private OswlUserPrincipal userWithPermissions(Set<Permission> perms) {
        return new OswlUserPrincipal(
                2L, "user@test.com", "hash", "User",
                false, true, List.of(), Set.of(), perms, false);
    }

    @Test
    @DisplayName("null principal → 빈 목록")
    void accessibleTabsFor_nullPrincipal_returnsEmpty() {
        assertThat(SettingsTabAccess.accessibleTabsFor(null)).isEmpty();
    }

    @Test
    @DisplayName("systemAdmin → 모든 탭 포함 (admin, security, notifications, ai, vcs, cli, cache)")
    void accessibleTabsFor_systemAdmin_allTabs() {
        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(adminPrincipal());

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey)
                .containsExactlyInAnyOrder("admin", "security", "notifications", "license-policy", "ai", "vcs", "cli", "cache");
    }

    @Test
    @DisplayName("SETTINGS_AI_MANAGE 권한 → ai 탭만 포함")
    void accessibleTabsFor_aiManagePermission_includesAiTab() {
        OswlUserPrincipal p = userWithPermissions(EnumSet.of(Permission.SETTINGS_AI_MANAGE));

        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(p);

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey)
                .containsExactly("ai")
                .doesNotContain("admin", "security", "vcs", "cli", "cache");
    }

    @Test
    @DisplayName("SETTINGS_VCS_MANAGE 권한 → vcs 탭만 포함")
    void accessibleTabsFor_vcsManagePermission_includesVcsTab() {
        OswlUserPrincipal p = userWithPermissions(EnumSet.of(Permission.SETTINGS_VCS_MANAGE));

        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(p);

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey).containsExactly("vcs");
    }

    @Test
    @DisplayName("SETTINGS_CLI_KEY_MANAGE 권한 → cli 탭만 포함")
    void accessibleTabsFor_cliKeyManagePermission_includesCliTab() {
        OswlUserPrincipal p = userWithPermissions(EnumSet.of(Permission.SETTINGS_CLI_KEY_MANAGE));

        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(p);

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey).containsExactly("cli");
    }

    @Test
    @DisplayName("SETTINGS_CACHE_MANAGE 권한 → cache 탭만 포함")
    void accessibleTabsFor_cacheManagePermission_includesCacheTab() {
        OswlUserPrincipal p = userWithPermissions(EnumSet.of(Permission.SETTINGS_CACHE_MANAGE));

        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(p);

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey).containsExactly("cache");
    }

    @Test
    @DisplayName("권한 없는 일반 사용자 → 빈 목록")
    void accessibleTabsFor_noPermissions_returnsEmpty() {
        OswlUserPrincipal p = userWithPermissions(EnumSet.noneOf(Permission.class));

        assertThat(SettingsTabAccess.accessibleTabsFor(p)).isEmpty();
    }

    @Test
    @DisplayName("AI+VCS 복합 권한 → ai, vcs 탭 포함")
    void accessibleTabsFor_multiplePermissions_includesMultipleTabs() {
        OswlUserPrincipal p = userWithPermissions(
                EnumSet.of(Permission.SETTINGS_AI_MANAGE, Permission.SETTINGS_VCS_MANAGE));

        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(p);

        assertThat(tabs).extracting(SettingsTabAccess.TabSpec::getKey)
                .containsExactlyInAnyOrder("ai", "vcs");
    }
}
