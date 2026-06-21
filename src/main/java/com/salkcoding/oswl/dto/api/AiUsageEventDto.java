package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class AiUsageEventDto {
    private final LocalDateTime createdAt;
    private final String operation;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final BigDecimal estimatedCostUsd;
    private final String modelName;
}
