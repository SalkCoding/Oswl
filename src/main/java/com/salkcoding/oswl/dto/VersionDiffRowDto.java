package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 버전 비교 테이블의 한 행.
 * 라이브러리가 한쪽 스캔에만 존재할 때는 양쪽 먹 또는 뗀음이 null일 수 있다.
 */
@Getter
@Builder
public class VersionDiffRowDto {

    public enum ChangeType { ADDED, REMOVED, UPDATED, NEW_THREAT, UNCHANGED }

    // ── 왼쪽 (fromScan) ───────────────────────────────────────────────
    private final String fromName;
    private final String fromVersion;
    private final String fromRiskLevel;   // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE" (화면에는 "Unscored"로 표시)

    // ── 오른쪽 (toScan) ──────────────────────────────────────────────
    private final String toName;
    private final String toVersion;
    private final String toRiskLevel;

    // ── 변경 메타데이터 ───────────────────────────────────────────────────
    private final ChangeType changeType;
}
