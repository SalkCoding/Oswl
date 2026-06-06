package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiDailyUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageLimiterService {

    private final AiDailyUsageRepository usageRepository;
    private final AiPreferencesService preferencesService;

    /**
     * @return true if a call may proceed; false if daily cap exceeded (cap 0 = unlimited)
     */
    @Transactional
    public boolean tryConsume(AiProvider provider) {
        int cap = preferencesService.getEffective().getDailyCallCap();
        if (cap <= 0) return true;

        LocalDate today = LocalDate.now();
        AiDailyUsage usage = usageRepository.findByUsageDateAndProvider(today, provider)
                .orElseGet(() -> usageRepository.save(AiDailyUsage.builder()
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
