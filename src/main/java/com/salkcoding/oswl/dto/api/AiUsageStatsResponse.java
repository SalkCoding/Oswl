package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class AiUsageStatsResponse {
    private final AiProvider provider;
    private final int todayCallCount;
    private final long todayPromptTokens;
    private final long todayCompletionTokens;
    private final long todayTotalTokens;
    private final BigDecimal todayEstimatedCostUsd;
    private final int dailyCallCap;
    private final List<AiUsageDailySummaryDto> dailySummaries;
}
