package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiDailyUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the daily-cap limiter: increments must persist even when the caller
 * runs inside a readOnly transaction (all AiAnalysisService methods do). Each test uses a
 * different provider because REQUIRES_NEW commits are not rolled back between tests.
 */
@SpringBootTest
@DisplayName("AiUsageLimiterService 통합 테스트")
class AiUsageLimiterServiceIntegrationTest {

    @Autowired AiUsageLimiterService usageLimiter;
    @Autowired AiPreferencesService preferencesService;
    @Autowired AiDailyUsageRepository usageRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private void saveCap(int cap) {
        preferencesService.save("en", 10, 8, "CRITICAL,HIGH", null, null, cap, null, null);
    }

    @AfterEach
    void resetCap() {
        // The preferences row is a singleton shared with other @SpringBootTest classes.
        saveCap(0);
    }

    private int countOf(AiProvider provider) {
        return usageRepository.findByUsageDateAndProvider(LocalDate.now(), provider)
                .map(AiDailyUsage::getCallCount)
                .orElse(-1);
    }

    @Test
    @DisplayName("readOnly 트랜잭션 안에서 호출핏도 사용량이 커밋된다")
    void tryConsume_insideReadOnlyTransaction_persistsIncrement() {
        saveCap(3);

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);
        Boolean allowed = readOnlyTx.execute(status -> usageLimiter.tryConsume(AiProvider.OPENAI));

        assertThat(allowed).isTrue();
        assertThat(countOf(AiProvider.OPENAI)).isEqualTo(1);
    }

    @Test
    @DisplayName("cap 도달 시 false를 반환하고 카운트는 cap을 넘지 않는다")
    void tryConsume_atCap_returnsFalseAndNeverExceedsCap() {
        saveCap(2);

        assertThat(usageLimiter.tryConsume(AiProvider.ANTHROPIC)).isTrue();
        assertThat(usageLimiter.tryConsume(AiProvider.ANTHROPIC)).isTrue();
        assertThat(usageLimiter.tryConsume(AiProvider.ANTHROPIC)).isFalse();

        assertThat(countOf(AiProvider.ANTHROPIC)).isEqualTo(2);
    }

    @Test
    @DisplayName("cap 0이면 무제한이며 사용량 row를 만들지 않는다")
    void tryConsume_capZero_unlimitedAndNoRowCreated() {
        saveCap(0);

        assertThat(usageLimiter.tryConsume(AiProvider.GEMINI)).isTrue();
        assertThat(countOf(AiProvider.GEMINI)).isEqualTo(-1);
    }
}
