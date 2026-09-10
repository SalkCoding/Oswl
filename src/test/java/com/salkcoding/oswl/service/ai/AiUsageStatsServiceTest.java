package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiCacheSumsDto;
import com.salkcoding.oswl.dto.api.AiUsageStatsResponse;
import com.salkcoding.oswl.dto.api.AiUsageSumsDto;
import com.salkcoding.oswl.repository.ai.AiDailyUsageRepository;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.repository.ai.AiUsageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AiUsageStatsService 단위 테스트")
class AiUsageStatsServiceTest {

    private AiDailyUsageRepository dailyUsageRepository;
    private AiUsageStatsService statsService;

    @BeforeEach
    void setUp() {
        dailyUsageRepository = mock(AiDailyUsageRepository.class);
        AiUsageLimiterService usageLimiter = mock(AiUsageLimiterService.class);
        AiSettingRepository aiSettingRepository = mock(AiSettingRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC);

        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(
                AiSetting.builder().provider(AiProvider.OPENAI).active(true).build()));
        when(dailyUsageRepository.sumForDate(any(), any()))
                .thenReturn(new AiUsageSumsDto(0, 0, 0, BigDecimal.ZERO));
        when(dailyUsageRepository.dailyTotalsSince(any(), any())).thenReturn(List.of());
        when(usageLimiter.getTodayCount(any())).thenReturn(0);
        when(usageLimiter.getDailyCallCap()).thenReturn(0);

        statsService = new AiUsageStatsService(
                mock(AiUsageEventRepository.class), dailyUsageRepository,
                usageLimiter, aiSettingRepository, clock);
    }

    @Test
    @DisplayName("캐시 합계가 응답에 그대로 담기고 절감 비용은 미스당 평균 비용 × 히트로 추정된다")
    void cacheSumsExposedWithAvoidedCostEstimate() {
        when(dailyUsageRepository.cacheSums(any()))
                .thenReturn(new AiCacheSumsDto(30, 10, new BigDecimal("0.120000")));

        AiUsageStatsResponse stats = statsService.getStats();

        assertThat(stats.getCacheHitCount()).isEqualTo(30);
        assertThat(stats.getCacheMissCount()).isEqualTo(10);
        // 0.12 / 10 per item × 30 hits
        assertThat(stats.getEstimatedAvoidedCostUsd()).isEqualByComparingTo(new BigDecimal("0.360000"));
    }

    @Test
    @DisplayName("미스가 없으면 항목당 비용을 알 수 없어 절감 비용 추정치는 null이다")
    void avoidedCostNullWithoutMisses() {
        when(dailyUsageRepository.cacheSums(any()))
                .thenReturn(new AiCacheSumsDto(15, 0, new BigDecimal("0.500000")));

        AiUsageStatsResponse stats = statsService.getStats();

        assertThat(stats.getCacheHitCount()).isEqualTo(15);
        assertThat(stats.getEstimatedAvoidedCostUsd()).isNull();
    }

    @Test
    @DisplayName("캐시 기록이 전혀 없으면 카운터는 0이고 추정치는 null이다")
    void emptyCacheSums() {
        when(dailyUsageRepository.cacheSums(any()))
                .thenReturn(new AiCacheSumsDto(0, 0, BigDecimal.ZERO));

        AiUsageStatsResponse stats = statsService.getStats();

        assertThat(stats.getCacheHitCount()).isZero();
        assertThat(stats.getCacheMissCount()).isZero();
        assertThat(stats.getEstimatedAvoidedCostUsd()).isNull();
    }

    @Test
    @DisplayName("활성 프로바이더가 없어도 캐시 집계는 null 프로바이더로 조회된다")
    void worksWithoutActiveProvider() {
        AiSettingRepository aiSettingRepository = mock(AiSettingRepository.class);
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(dailyUsageRepository.cacheSums(isNull()))
                .thenReturn(new AiCacheSumsDto(3, 3, new BigDecimal("0.060000")));
        AiUsageStatsService noProvider = new AiUsageStatsService(
                mock(AiUsageEventRepository.class), dailyUsageRepository,
                mock(AiUsageLimiterService.class), aiSettingRepository,
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC));

        AiUsageStatsResponse stats = noProvider.getStats();

        assertThat(stats.getCacheHitCount()).isEqualTo(3);
        assertThat(stats.getEstimatedAvoidedCostUsd()).isEqualByComparingTo(new BigDecimal("0.060000"));
    }
}
