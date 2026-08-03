package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiDailyUsage;
import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.ai.AiDailyUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiUsageLimiterServiceTest {

    @Mock AiDailyUsageRepository usageRepository;
    @Mock AiPreferencesService preferencesService;
    @Mock PlatformTransactionManager transactionManager;

    @InjectMocks AiUsageLimiterService usageLimiter;

    private void stubCap(int cap) {
        lenient().when(preferencesService.getEffective()).thenReturn(
                AiPreferences.defaults("en", 10, 8, "CRITICAL,HIGH", cap));
    }

    @Test
    void tryConsume_capZero_isUnlimited_andTouchesNoUsageRow() {
        stubCap(0);

        assertThat(usageLimiter.tryConsume(AiProvider.OPENAI)).isTrue();

        verifyNoInteractions(usageRepository, transactionManager);
    }

    @Test
    void tryConsume_belowCap_incrementsAndReturnsTrue() {
        stubCap(5);
        AiDailyUsage row = AiDailyUsage.builder()
                .usageDate(LocalDate.now()).provider(AiProvider.OPENAI).callCount(2).build();
        when(usageRepository.findLockedByUsageDateAndProvider(any(), any())).thenReturn(Optional.of(row));

        assertThat(usageLimiter.tryConsume(AiProvider.OPENAI)).isTrue();

        assertThat(row.getCallCount()).isEqualTo(3);
        verify(usageRepository).save(row);
    }

    @Test
    void tryConsume_atCap_returnsFalseWithoutIncrement() {
        stubCap(5);
        AiDailyUsage row = AiDailyUsage.builder()
                .usageDate(LocalDate.now()).provider(AiProvider.OPENAI).callCount(5).build();
        when(usageRepository.findLockedByUsageDateAndProvider(any(), any())).thenReturn(Optional.of(row));

        assertThat(usageLimiter.tryConsume(AiProvider.OPENAI)).isFalse();

        assertThat(row.getCallCount()).isEqualTo(5);
        verify(usageRepository, never()).save(any());
    }

    @Test
    void tryConsume_noRow_createsItWithCountOne() {
        stubCap(5);
        when(usageRepository.findLockedByUsageDateAndProvider(any(), any())).thenReturn(Optional.empty());
        when(usageRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(usageLimiter.tryConsume(AiProvider.OPENAI)).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(AiDailyUsage.class);
        verify(usageRepository).saveAndFlush(captor.capture());
        // The new row (created with count 0) is then incremented for the current call.
        verify(usageRepository).save(captor.getValue());
        assertThat(captor.getValue().getCallCount()).isEqualTo(1);
    }

    @Test
    void tryConsume_concurrentInsert_retriesOnceInFreshTransaction() {
        stubCap(5);
        AiDailyUsage competitorRow = AiDailyUsage.builder()
                .usageDate(LocalDate.now()).provider(AiProvider.OPENAI).callCount(0).build();
        when(usageRepository.findLockedByUsageDateAndProvider(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(competitorRow));
        when(usageRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(usageLimiter.tryConsume(AiProvider.OPENAI)).isTrue();

        verify(usageRepository, times(2)).findLockedByUsageDateAndProvider(any(), any());
        assertThat(competitorRow.getCallCount()).isEqualTo(1);
    }
}
