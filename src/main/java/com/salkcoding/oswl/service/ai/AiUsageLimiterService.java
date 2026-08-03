package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.ai.AiDailyUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageLimiterService {

    private final AiDailyUsageRepository usageRepository;
    private final AiPreferencesService preferencesService;
    private final PlatformTransactionManager transactionManager;

    /**
     * @return true if a call may proceed; false if daily cap exceeded (cap 0 = unlimited)
     */
    public boolean tryConsume(AiProvider provider) {
        int cap = preferencesService.getEffective().getDailyCallCap();
        if (cap <= 0) return true;

        // Always a fresh read-write transaction: callers (AiAnalysisService) run readOnly
        // transactions where the increment would silently never be flushed.
        TransactionTemplate writeTx = new TransactionTemplate(transactionManager);
        writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return Boolean.TRUE.equals(writeTx.execute(status -> consumeOnce(provider, cap)));
        } catch (DataIntegrityViolationException concurrentInsert) {
            // A concurrent request created today's row first; its transaction is already
            // rolled back, so retry once against a fresh one (PostgreSQL would refuse to
            // continue in the aborted transaction anyway).
            return Boolean.TRUE.equals(writeTx.execute(status -> consumeOnce(provider, cap)));
        }
    }

    private boolean consumeOnce(AiProvider provider, int cap) {
        LocalDate today = LocalDate.now();
        // Pessimistic lock serializes the check-then-increment across concurrent callers.
        AiDailyUsage usage = usageRepository.findLockedByUsageDateAndProvider(today, provider)
                .orElseGet(() -> usageRepository.saveAndFlush(AiDailyUsage.builder()
                        .usageDate(today)
                        .provider(provider)
                        .callCount(0)
                        .build()));

        if (usage.getCallCount() >= cap) {
            log.warn("[AI] Daily cap reached for {} ({}/{})", provider, usage.getCallCount(), cap);
            return false;
        }
        usage.increment();
        usageRepository.save(usage);
        return true;
    }

    @Transactional(readOnly = true)
    public int getTodayCount(AiProvider provider) {
        return usageRepository.findByUsageDateAndProvider(LocalDate.now(), provider)
                .map(AiDailyUsage::getCallCount)
                .orElse(0);
    }

    /** @return true when {@code dailyCallCap > 0} and today's usage has reached the cap */
    @Transactional(readOnly = true)
    public boolean isCapReached(AiProvider provider) {
        int cap = preferencesService.getEffective().getDailyCallCap();
        if (cap <= 0) return false;
        return getTodayCount(provider) >= cap;
    }

    @Transactional(readOnly = true)
    public int getDailyCallCap() {
        return preferencesService.getEffective().getDailyCallCap();
    }
}
