package com.salkcoding.oswl.dto.scan;

import com.salkcoding.oswl.domain.enums.RiskLevel;

/** Only the coordinates and highest severity are needed to compare scan versions. */
public record VersionDiffComponent(String name, String version, Integer riskRank) {
    public String getName() { return name; }
    public String getVersion() { return version; }
    public RiskLevel highestSeverity() {
        return switch (riskRank) {
            case 0 -> RiskLevel.CRITICAL;
            case 1 -> RiskLevel.HIGH;
            case 2 -> RiskLevel.MEDIUM;
            case 3 -> RiskLevel.LOW;
            default -> RiskLevel.NONE;
        };
    }
}
