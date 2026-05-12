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

        if (principal.isSystemAdmin()) {
            tabs.add(new TabSpec("admin", "🔐", "Administration"));
        }
        if (principal.isSystemAdmin()) {
            tabs.add(new TabSpec("security", "🛡️", "Security"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_AI_MANAGE)) {
            tabs.add(new TabSpec("ai", "🤖", "AI Settings"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_VCS_MANAGE)) {
            tabs.add(new TabSpec("vcs", "🔗", "VCS Connections"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CLI_KEY_MANAGE)) {
            tabs.add(new TabSpec("cli", "🔑", "CLI API Keys"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CACHE_MANAGE)) {
            tabs.add(new TabSpec("cache", "⚡", "Cache Settings"));
        }
        return tabs;
    }
}
