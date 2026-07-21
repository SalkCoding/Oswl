package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class AiUsageEventDto {
    private final LocalDateTime createdAt;
    private final AiProvider provider;
    private final String operation;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final BigDecimal estimatedCostUsd;
    private final String modelName;
    private final String projectName;
}
