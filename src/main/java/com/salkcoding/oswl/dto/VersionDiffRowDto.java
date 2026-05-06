package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * One row in the version-diff comparison table.
 * Either (or both) sides can be null when a library is only in one scan.
 */
@Getter
@Builder
public class VersionDiffRowDto {

    public enum ChangeType { ADDED, REMOVED, UPDATED, NEW_THREAT, UNCHANGED }

    // ── Left side (fromScan) ──────────────────────────────────────────────
    private final String fromName;
    private final String fromVersion;
    private final String fromRiskLevel;   // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE"

    // ── Right side (toScan) ──────────────────────────────────────────────
    private final String toName;
    private final String toVersion;
    private final String toRiskLevel;

    // ── Change metadata ───────────────────────────────────────────────────
    private final ChangeType changeType;
}
