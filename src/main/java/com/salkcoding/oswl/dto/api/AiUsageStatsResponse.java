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
    /** All-time context-hash cache counters (item level); 0/0 when no scan has run yet. */
    private final long cacheHitCount;
    private final long cacheMissCount;
    /** Rough avoided-cost estimate (average cost per summarized item × cache hits); null when no misses are recorded yet. */
    private final BigDecimal estimatedAvoidedCostUsd;
}
