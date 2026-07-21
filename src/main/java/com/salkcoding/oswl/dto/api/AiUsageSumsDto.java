package com.salkcoding.oswl.dto.api;

import java.math.BigDecimal;

/** JPQL constructor projection for the per-day token/cost totals over {@code AiDailyUsage}. */
public record AiUsageSumsDto(long promptTokens, long completionTokens, long totalTokens,
                             BigDecimal estimatedCostUsd) {
}
