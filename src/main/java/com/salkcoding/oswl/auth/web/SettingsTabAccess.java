package com.salkcoding.oswl.auth.web;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public class SettingsTabAccess {

    @Data
    @AllArgsConstructor
    public static class TabSpec {
        private String key;     // admin / ai / vcs / cli / cache
        private String icon;    // emoji
        private String label;
    }

    public static List<TabSpec> accessibleTabsFor(OswlUserPrincipal principal) {
        List<TabSpec> tabs = new ArrayList<>();
        if (principal == null) return tabs;

        if (principal.isSuperAdmin()) {
            tabs.add(new TabSpec("admin", "🔐", "관리자 설정"));
        }
        if (principal.isSuperAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_AI_MANAGE)) {
            tabs.add(new TabSpec("ai", "🤖", "AI 설정"));
        }
        if (principal.isSuperAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_VCS_MANAGE)) {
            tabs.add(new TabSpec("vcs", "🔗", "VCS 연결"));
        }
        if (principal.isSuperAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CLI_KEY_MANAGE)) {
            tabs.add(new TabSpec("cli", "🔑", "CLI 키"));
        }
        if (principal.isSuperAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CACHE_MANAGE)) {
            tabs.add(new TabSpec("cache", "⚡", "캐시"));
        }
        return tabs;
    }
}
