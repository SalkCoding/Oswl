package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class AiUsageDailySummaryDto {
    private final LocalDate date;
    private final long totalTokens;
    private final BigDecimal estimatedCostUsd;
    private final long callCount;
}
