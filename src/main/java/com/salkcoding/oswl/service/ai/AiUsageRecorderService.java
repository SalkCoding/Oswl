package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiDailyUsage;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.entity.ai.AiUsageEvent;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.ai.AiDailyUsageRepository;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.repository.ai.AiUsageEventRepository;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorderService {

    /** Raw events are kept only for the recent-calls list; daily stats live in AiDailyUsage. */
    private static final int MAX_EVENTS = 100;

    private final AiUsageEventRepository eventRepository;
    private final AiDailyUsageRepository dailyUsageRepository;
    private final AiSettingRepository aiSettingRepository;
    private final Clock clock;
    /** Null in plain-Mockito unit tests (no Spring context) — every use is guarded. */
    private final OswlMetrics oswlMetrics;

    @Value("${oswl.ai.pricing.openai-input-per-1m:2.50}")
    private double openAiInputPer1M;

    @Value("${oswl.ai.pricing.openai-output-per-1m:10.00}")
    private double openAiOutputPer1M;

    @Value("${oswl.ai.pricing.anthropic-input-per-1m:3.00}")
    private double anthropicInputPer1M;

    @Value("${oswl.ai.pricing.anthropic-output-per-1m:15.00}")
    private double anthropicOutputPer1M;

    @Value("${oswl.ai.pricing.gemini-input-per-1m:1.25}")
    private double geminiInputPer1M;

    @Value("${oswl.ai.pricing.gemini-output-per-1m:5.00}")
    private double geminiOutputPer1M;

    @Value("${oswl.ai.pricing.local-input-per-1m:0}")
    private double localInputPer1M;

    @Value("${oswl.ai.pricing.local-output-per-1m:0}")
    private double localOutputPer1M;

    /** Parses OpenAI-compatible {@code usage} block from a chat completion response. */
    // Always a fresh read-write transaction: callers (AiAnalysisService) run readOnly
    // transactions where the writes would silently never be flushed, and the event save,
    // FIFO trim and daily upsert must commit atomically.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFromOpenAiUsage(Map<String, Object> body, AiProvider provider,
                                      String operation, String modelName) {
        if (body == null) return;
        Object usageObj = body.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) return;
        int prompt = intVal(usage.get("prompt_tokens"));
        int completion = intVal(usage.get("completion_tokens"));
        if (prompt == 0 && completion == 0) {
            prompt = intVal(usage.get("input_tokens"));
            completion = intVal(usage.get("output_tokens"));
        }
        record(provider, operation, modelName, prompt, completion);
    }

    /**
     * Parses Anthropic Messages API usage block. F4: {@code cache_creation_input_tokens}/
     * {@code cache_read_input_tokens} are real prompt content Anthropic still processed (a
     * cache read is billed at a discount, not for free) and are excluded from
     * {@code input_tokens} by the API — folding them into the recorded prompt total keeps
     * token/usage stats accurate. {@link AiModelPricing} already documents that its list
     * prices ignore the cache discount, so cost is a conservative (slightly high) estimate
     * whenever the cache is actually hit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFromAnthropicUsage(Map<String, Object> body, AiProvider provider,
                                         String operation, String modelName) {
        if (body == null) return;
        Object usageObj = body.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) return;
        int input = intVal(usage.get("input_tokens"));
        int cacheCreation = intVal(usage.get("cache_creation_input_tokens"));
        int cacheRead = intVal(usage.get("cache_read_input_tokens"));
        if (cacheCreation > 0 || cacheRead > 0) {
            log.debug("[AI][Anthropic][Cache] op={} model={} cacheCreation={} cacheRead={} freshInput={}",
                    operation, modelName, cacheCreation, cacheRead, input);
        }
        record(provider, operation, modelName, input + cacheCreation + cacheRead, intVal(usage.get("output_tokens")));
    }

    /**
     * Counts context-hash cache outcomes from a scan enrichment batch. Hits/misses are item
     * level (one per CVE/license candidate), not per API call — a hit means the previous
     * summary was reused and no tokens were spent on that item. Recorded on the daily
     * aggregate (not as usage events) so the hit rate survives the raw-event FIFO cap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCacheOutcomes(int hits, int misses) {
        if (hits <= 0 && misses <= 0) return;
        AiProvider provider = aiSettingRepository.findByActiveTrue()
                .map(AiSetting::getProvider)
                .orElse(null);
        if (provider == null) return;
        LocalDate today = LocalDate.now(clock);
        AiDailyUsage daily = dailyUsageRepository.findLockedByUsageDateAndProvider(today, provider)
                .orElseGet(() -> AiDailyUsage.builder()
                        .usageDate(today)
                        .provider(provider)
                        .callCount(0)
                        .build());
        daily.accumulateCacheOutcomes(hits, misses);
        dailyUsageRepository.save(daily);
        log.debug("[AI][Usage] {} context-cache hits={} misses={}", provider, hits, misses);
    }

    private void record(AiProvider provider, String operation, String modelName,
                        int promptTokens, int completionTokens) {
        if (provider == null || operation == null) return;
        int prompt = Math.max(0, promptTokens);
        int completion = Math.max(0, completionTokens);
        int total = prompt + completion;
        BigDecimal cost = estimateCost(provider, modelName, prompt, completion);

        LocalDate today = LocalDate.now(clock);
        eventRepository.save(AiUsageEvent.builder()
                .usageDate(today)
                .provider(provider)
                .operation(operation)
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .estimatedCostUsd(cost)
                .modelName(modelName)
                .projectName(AiUsageContext.currentProject())
                .branch(AiUsageContext.currentBranch())
                .build());
        trimToMaxEvents();
        upsertDailyUsage(today, provider, prompt, completion, cost);
        if (oswlMetrics != null) {
            oswlMetrics.recordAiUsage(provider.name(), prompt, completion, cost.doubleValue());
        }

        log.debug("[AI][Usage] {} {} tokens={} cost=${}", provider, operation, total, cost);
    }

    /** FIFO cap: drops the oldest rows once the table grows past {@link #MAX_EVENTS}. */
    private void trimToMaxEvents() {
        long excess = eventRepository.count() - MAX_EVENTS;
        if (excess <= 0) return;
        List<Long> oldestIds = eventRepository.findOldestIds(PageRequest.of(0, (int) excess));
        eventRepository.deleteAllByIdInBatch(oldestIds);
    }

    /** Accumulates tokens/cost into the daily aggregate so stats survive the event FIFO cap. */
    private void upsertDailyUsage(LocalDate date, AiProvider provider,
                                  int prompt, int completion, BigDecimal cost) {
        // Row lock serializes against AiUsageLimiterService, which writes the same row.
        AiDailyUsage daily = dailyUsageRepository.findLockedByUsageDateAndProvider(date, provider)
                .orElseGet(() -> AiDailyUsage.builder()
                        .usageDate(date)
                        .provider(provider)
                        .callCount(0)
                        .build());
        daily.accumulateUsage(prompt, completion, cost);
        dailyUsageRepository.save(daily);
    }

    /**
     * Prefers the published per-model list price; a flat per-provider rate is only a fallback
     * for models with no known price (custom deployments, self-hosted, newly released ids).
     * Using one rate for a whole provider mis-estimates by an order of magnitude between that
     * provider's cheapest and most expensive models.
     */
    private BigDecimal estimateCost(AiProvider provider, String modelName,
                                    int promptTokens, int completionTokens) {
        // A locally hosted model costs nothing to call regardless of what it is named, so the
        // provider rate (0 by default) wins over any list price its id happens to collide with.
        if (provider != AiProvider.LOCAL) {
            var listPrice = AiModelPricing.estimate(modelName, promptTokens, completionTokens);
            if (listPrice.isPresent()) {
                return listPrice.get().setScale(6, RoundingMode.HALF_UP);
            }
        }

        double inRate;
        double outRate;
        switch (provider) {
            case ANTHROPIC -> {
                inRate = anthropicInputPer1M;
                outRate = anthropicOutputPer1M;
            }
            case GEMINI -> {
                inRate = geminiInputPer1M;
                outRate = geminiOutputPer1M;
            }
            case LOCAL -> {
                inRate = localInputPer1M;
                outRate = localOutputPer1M;
            }
            default -> {
                inRate = openAiInputPer1M;
                outRate = openAiOutputPer1M;
            }
        }
        double usd = (promptTokens / 1_000_000.0) * inRate + (completionTokens / 1_000_000.0) * outRate;
        return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP);
    }

    private static int intVal(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }
}
