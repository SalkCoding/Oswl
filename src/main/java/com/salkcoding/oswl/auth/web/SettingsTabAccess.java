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
        private String key;      // admin / ai / vcs / cli / cache
        private String icon;     // emoji
        /** i18n message key resolved in the template (e.g. settings.tab.admin). */
        private String labelKey;
    }

    public static List<TabSpec> accessibleTabsFor(OswlUserPrincipal principal) {
        List<TabSpec> tabs = new ArrayList<>();
        if (principal == null) return tabs;

        // The Administration tab hosts four panels with different owners: user management and
        // role templates stay SYSTEM_ADMIN-only (enforced per-panel in the template and by each
        // API), while the audit log and offline snapshot are delegatable. Without this, holders
        // of AUDIT_LOG_VIEW / SETTINGS_SNAPSHOT_MANAGE could be granted those permissions but
        // never reach the screen that uses them.
        if (principal.isSystemAdmin()
                || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.AUDIT_LOG_VIEW)
                || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.AUDIT_LOG_EXPORT)
                || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_SNAPSHOT_MANAGE)) {
            tabs.add(new TabSpec("admin", "🔐", "settings.tab.admin"));
        }
        if (principal.isSystemAdmin()
                || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_SECURITY_MANAGE)) {
            tabs.add(new TabSpec("security", "🛡️", "settings.tab.security"));
        }
        if (principal.isSystemAdmin()
                || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.LICENSE_POLICY_MANAGE)) {
            tabs.add(new TabSpec("license-policy", "📋", "settings.tab.licensePolicy"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_AI_MANAGE)) {
            tabs.add(new TabSpec("ai", "🤖", "settings.tab.ai"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_VCS_MANAGE)) {
            tabs.add(new TabSpec("vcs", "🔗", "settings.tab.vcs"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CLI_KEY_MANAGE)) {
            tabs.add(new TabSpec("cli", "🔑", "settings.tab.cli"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_CACHE_MANAGE)) {
            tabs.add(new TabSpec("cache", "⚡", "settings.tab.cache"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_WEBHOOK_MANAGE)) {
            tabs.add(new TabSpec("webhooks", "🔔", "settings.tab.webhooks"));
        }
        if (principal.isSystemAdmin() || principal.hasPermission(com.salkcoding.oswl.auth.enums.Permission.SETTINGS_REPORTING_MANAGE)) {
            tabs.add(new TabSpec("reports", "🖨️", "settings.tab.reports"));
        }
        // Read-only infra/connectivity checks — SYSTEM_ADMIN only (no delegated permission,
        // unlike the other tabs: it surfaces DB/SMTP/AI/VCS reachability details in one place).
        if (principal.isSystemAdmin()) {
            tabs.add(new TabSpec("diagnostics", "🩺", "settings.tab.diagnostics"));
        }
        // Config export/import — SYSTEM_ADMIN only, mirrors the admin tab's own
        // security posture (role templates, license policy) rather than a delegatable permission.
        if (principal.isSystemAdmin()) {
            tabs.add(new TabSpec("config-transfer", "📦", "settings.tab.configTransfer"));
        }
        return tabs;
    }
}
