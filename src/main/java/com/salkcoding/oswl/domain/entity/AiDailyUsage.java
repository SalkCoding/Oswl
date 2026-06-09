package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "call_count", nullable = false)
    private int callCount;

    public void increment() {
        this.callCount++;
    }
}
