package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_usage_events",
        indexes = {
                @Index(name = "ix_ai_usage_events_date_provider", columnList = "usage_date, provider"),
                @Index(name = "ix_ai_usage_events_date_created", columnList = "usage_date, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AiUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiProvider provider;

    @Column(nullable = false, length = 64)
    private String operation;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Column(name = "model_name", length = 128)
    private String modelName;

    /** Denormalized name of the project this call was made for (null for non-project calls, e.g. connection tests). */
    @Column(name = "project_name", length = 160)
    private String projectName;

    /** Branch / scan version the AI call was attributed to; null for non-scan calls. */
    @Column(name = "branch", length = 160)
    private String branch;
}
