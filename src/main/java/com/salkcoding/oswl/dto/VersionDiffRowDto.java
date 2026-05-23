package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * A single row in the version comparison table.
 * When a library exists in only one scan, fields on the missing side may be null.
 */
@Getter
@Builder
public class VersionDiffRowDto {

    public enum ChangeType { ADDED, REMOVED, UPDATED, NEW_THREAT, UNCHANGED }

    // ── Left side (fromScan) ──────────────────────────────────────────
    private final String fromName;
    private final String fromVersion;
    private final String fromRiskLevel;   // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE" (displayed as "Unscored" in the UI)

    // ── Right side (toScan) ───────────────────────────────────────────
    private final String toName;
    private final String toVersion;
    private final String toRiskLevel;

    // ── Change metadata ────────────────────────────────────────────────
    private final ChangeType changeType;
}
