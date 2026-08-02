package com.salkcoding.oswl.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Permission {
    PROJECT_VIEW("View Projects"),
    PROJECT_CREATE("Create Projects"),
    // Checked by ProjectContextController#updateDeploymentProfile. It was missing from this enum,
    // so no role template could grant it and only SYSTEM_ADMIN could change a project's
    // deployment profile.
    PROJECT_UPDATE("Update Project Settings"),
    PROJECT_DELETE("Soft Delete Projects"),
    PROJECT_RESTORE("Restore Projects"),
    PROJECT_PERMANENT_DELETE("Permanently Delete Projects"),

    SCAN_SUBMIT("Submit Scans"),
    SCAN_VIEW("View Scan Results"),
    SCAN_HISTORY_VIEW("View Scan History"),
    SCAN_HISTORY_DELETE("Delete Scan History"),

    SECURITY_CENTER_VIEW("View Security Center"),
    SECURITY_CENTER_UPDATE_STATUS("Update Vulnerability Status"),
    SECURITY_CENTER_EXPORT("Export Results"),

    LICENSE_VIEW("View License Analysis"),
    LICENSE_EXPORT("Export License Reports"),
    LICENSE_POLICY_MANAGE("Manage License Policy"),

    COMPONENT_DETAIL_VIEW("View Component Details"),
    VERSION_DIFF_VIEW("View Version Diff"),
    RISK_TREND_VIEW("View Risk Trends"),

    SETTINGS_AI_MANAGE("Manage AI Settings"),
    SETTINGS_VCS_MANAGE("Manage VCS Connections"),
    SETTINGS_CLI_KEY_MANAGE("Manage CLI API Keys"),
    SETTINGS_CACHE_MANAGE("Manage Cache Settings"),
    SETTINGS_SECURITY_MANAGE("Manage Security Settings"),

    // ── v1.0.4 capabilities — delegatable instead of SYSTEM_ADMIN-only ──
    ORG_DASHBOARD_VIEW("View Organization Dashboard"),
    AUDIT_LOG_VIEW("View Audit Log"),
    AUDIT_LOG_EXPORT("Export Audit Log (SIEM)"),
    SETTINGS_JIRA_MANAGE("Manage Jira Integration"),
    SETTINGS_SNAPSHOT_MANAGE("Manage Offline Snapshot Bundles"),

    // ── Organization structure — team hierarchy and membership management ──
    TEAM_MANAGE("Manage Teams");

    private final String description;
}
