package com.salkcoding.oswl.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class CveDto {
    private final String id;
    /** CRITICAL | HIGH | MEDIUM | LOW */
    private final String severity;
    private final double cvssScore;
    private final String type;
    private final String discoveredOn;
    private final String affects;
    private final String fixVersion;
    private final String aiSummary;
}
