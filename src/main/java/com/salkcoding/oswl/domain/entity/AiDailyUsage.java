package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "ai_daily_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ai_daily_usage",
                columnNames = {"usage_date", "provider"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AiDailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiProvider provider;

    /** Calls counted by the daily-cap limiter (stays 0 when no cap is configured). */
    @Column(name = "call_count", nullable = false)
    private int callCount;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 14, scale = 6)
    @Builder.Default
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    public void increment() {
        this.callCount++;
    }

    /** Adds one AI call's token/cost usage to this daily aggregate. */
    public void accumulateUsage(int promptTokens, int completionTokens, BigDecimal costUsd) {
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalTokens += promptTokens + completionTokens;
        this.estimatedCostUsd = this.estimatedCostUsd.add(costUsd);
    }
}
