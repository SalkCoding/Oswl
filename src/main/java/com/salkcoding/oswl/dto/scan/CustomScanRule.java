package com.salkcoding.oswl.dto.scan;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
public record CustomScanRule(String id, ScanFindingType type, RiskLevel severity, String description, String regex, String fileSuffix) {}
