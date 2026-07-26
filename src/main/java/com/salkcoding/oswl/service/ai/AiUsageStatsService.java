package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.entity.AiUsageEvent;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiUsageDailySummaryDto;
import com.salkcoding.oswl.dto.api.AiUsageEventDto;
import com.salkcoding.oswl.dto.api.AiUsageStatsResponse;
import com.salkcoding.oswl.dto.api.AiUsageSumsDto;
import com.salkcoding.oswl.repository.AiDailyUsageRepository;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.repository.AiUsageEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUsageStatsService {

    private static final int MAX_EVENTS_PAGE_SIZE = 50;

    private final AiUsageEventRepository eventRepository;
    private final AiDailyUsageRepository dailyUsageRepository;
    private final AiUsageLimiterService usageLimiter;
    private final AiSettingRepository aiSettingRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AiUsageStatsResponse getStats() {
        AiProvider active = aiSettingRepository.findByActiveTrue()
                .map(AiSetting::getProvider)
                .orElse(null);

        // Totals come from the daily aggregate table, not the FIFO-capped raw events,
        // so today's figures and the 7-day chart stay correct after old events are trimmed.
        LocalDate today = LocalDate.now(clock);
        AiUsageSumsDto sums = dailyUsageRepository.sumForDate(today, active);

        int callCount = active != null ? usageLimiter.getTodayCount(active) : 0;
        int cap = usageLimiter.getDailyCallCap();

        List<AiUsageDailySummaryDto> daily = dailyUsageRepository.dailyTotalsSince(today.minusDays(6), active)
                .stream()
                .map(row -> AiUsageDailySummaryDto.builder()
                        .date(row.date())
                        .totalTokens(row.totalTokens())
                        .estimatedCostUsd(scale6(row.estimatedCostUsd()))
                        .callCount(row.callCount())
                        .build())
                .toList();

        return AiUsageStatsResponse.builder()
                .provider(active)
                .todayCallCount(callCount)
                .todayPromptTokens(sums.promptTokens())
                .todayCompletionTokens(sums.completionTokens())
                .todayTotalTokens(sums.totalTokens())
                .todayEstimatedCostUsd(scale6(sums.estimatedCostUsd()))
                .dailyCallCap(cap)
                .dailySummaries(daily)
                .build();
    }

    /** Raw recent-call events, newest first. The table is FIFO-capped, so pages stay small. */
    @Transactional(readOnly = true)
    public Page<AiUsageEventDto> getEvents(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, MAX_EVENTS_PAGE_SIZE);
        return eventRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(safePage, safeSize))
                .map(AiUsageStatsService::toEventDto);
    }

    private static AiUsageEventDto toEventDto(AiUsageEvent e) {
        return AiUsageEventDto.builder()
                .createdAt(e.getCreatedAt())
                .provider(e.getProvider())
                .operation(e.getOperation())
                .promptTokens(e.getPromptTokens())
                .completionTokens(e.getCompletionTokens())
                .totalTokens(e.getTotalTokens())
                .estimatedCostUsd(e.getEstimatedCostUsd())
                .modelName(e.getModelName())
                .projectName(e.getProjectName())
                .branch(e.getBranch())
                .build();
    }

    private static BigDecimal scale6(BigDecimal value) {
        return (value != null ? value : BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
    }
}
