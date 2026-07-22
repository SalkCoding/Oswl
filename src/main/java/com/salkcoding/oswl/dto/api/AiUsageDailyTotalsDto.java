package com.salkcoding.oswl.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** JPQL constructor projection for one day's aggregated totals over {@code AiDailyUsage}. */
public record AiUsageDailyTotalsDto(LocalDate date, long totalTokens, BigDecimal estimatedCostUsd,
                                    long callCount) {
}
