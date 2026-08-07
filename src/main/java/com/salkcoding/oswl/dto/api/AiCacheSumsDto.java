package com.salkcoding.oswl.dto.api;

import java.math.BigDecimal;

/** JPQL constructor projection for the all-time context-hash cache sums over {@code AiDailyUsage}. */
public record AiCacheSumsDto(long hits, long misses, BigDecimal estimatedCostUsd) {
}
