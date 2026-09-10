package com.salkcoding.oswl.dto.scan;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;

/**
 * A secret/IaC finding detected by {@code SecretScanner} or {@code IacScanner} before it is
 * persisted as a {@link com.salkcoding.oswl.domain.entity.scan.ScanFinding}. Never carries the
 * matched secret value itself — only a fingerprint.
 */
public record ScanFindingCandidate(
        ScanFindingType type,
        String ruleId,
        RiskLevel severity,
        String filePath,
        Integer lineNumber,
        String description,
        String fingerprint) {
}
