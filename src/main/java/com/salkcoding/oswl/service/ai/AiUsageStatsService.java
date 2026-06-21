package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiUsageDailySummaryDto;
import com.salkcoding.oswl.dto.api.AiUsageEventDto;
import com.salkcoding.oswl.dto.api.AiUsageStatsResponse;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.repository.AiUsageEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUsageStatsService {

    private final AiUsageEventRepository eventRepository;
    private final AiUsageLimiterService usageLimiter;
    private final AiPreferencesService preferencesService;
    private final AiSettingRepository aiSettingRepository;

    @Transactional(readOnly = true)
    public AiUsageStatsResponse getStats() {
        AiProvider active = aiSettingRepository.findByActiveTrue()
                .map(s -> s.getProvider())
                .orElse(null);

        LocalDate today = LocalDate.now();
        Object[] sums = eventRepository.sumForDate(today, active);
        long promptToday = longAt(sums, 0);
        long completionToday = longAt(sums, 1);
        long totalToday = longAt(sums, 2);
        BigDecimal costToday = decimalAt(sums, 3);

        int callCount = active != null ? usageLimiter.getTodayCount(active) : 0;
        int cap = usageLimiter.getDailyCallCap();

        List<AiUsageEventDto> recent = eventRepository.findTop30ByUsageDateOrderByCreatedAtDesc(today).stream()
                .filter(e -> active == null || e.getProvider() == active)
                .limit(20)
                .map(e -> AiUsageEventDto.builder()
                        .createdAt(e.getCreatedAt())
                        .operation(e.getOperation())
                        .promptTokens(e.getPromptTokens())
                        .completionTokens(e.getCompletionTokens())
                        .totalTokens(e.getTotalTokens())
                        .estimatedCostUsd(e.getEstimatedCostUsd())
                        .modelName(e.getModelName())
                        .build())
                .toList();

        List<AiUsageDailySummaryDto> daily = new ArrayList<>();
        for (Object[] row : eventRepository.dailyTotalsSince(today.minusDays(6), active)) {
            daily.add(AiUsageDailySummaryDto.builder()
                    .date((LocalDate) row[0])
                    .totalTokens(longAt(row, 1))
                    .estimatedCostUsd(decimalAt(row, 2))
                    .callCount(longAt(row, 3))
                    .build());
        }

        return AiUsageStatsResponse.builder()
                .provider(active)
                .todayCallCount(callCount)
                .todayPromptTokens(promptToday)
                .todayCompletionTokens(completionToday)
                .todayTotalTokens(totalToday)
                .todayEstimatedCostUsd(costToday)
                .dailyCallCap(cap)
                .recentEvents(recent)
                .dailySummaries(daily)
                .pricingDisclaimer(
                        "Cost figures are rough estimates from configured per-million token rates, not provider invoices.")
                .build();
    }

    private static long longAt(Object[] arr, int idx) {
        if (arr == null || idx >= arr.length || arr[idx] == null) return 0L;
        if (arr[idx] instanceof Number n) return n.longValue();
        return 0L;
    }

    private static BigDecimal decimalAt(Object[] arr, int idx) {
        if (arr == null || idx >= arr.length || arr[idx] == null) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        if (arr[idx] instanceof BigDecimal bd) return bd.setScale(6, RoundingMode.HALF_UP);
        if (arr[idx] instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(6, RoundingMode.HALF_UP);
        return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    }
}
