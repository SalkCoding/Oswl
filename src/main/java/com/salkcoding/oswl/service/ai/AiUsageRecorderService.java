package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiUsageEvent;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiUsageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorderService {

    private final AiUsageEventRepository eventRepository;

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

    @Transactional
    void record(AiProvider provider, String operation, String modelName,
                       int promptTokens, int completionTokens) {
        if (provider == null || operation == null) return;
        int prompt = Math.max(0, promptTokens);
        int completion = Math.max(0, completionTokens);
        int total = prompt + completion;
        BigDecimal cost = estimateCost(provider, prompt, completion);

        LocalDate today = LocalDate.now();
        eventRepository.save(AiUsageEvent.builder()
                .usageDate(today)
                .provider(provider)
                .operation(operation)
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .estimatedCostUsd(cost)
                .modelName(modelName)
                .build());

        log.debug("[AI][Usage] {} {} tokens={} cost=${}", provider, operation, total, cost);
    }

    /** Parses OpenAI-compatible {@code usage} block from a chat completion response. */
    @SuppressWarnings("unchecked")
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

    /** Parses Anthropic Messages API usage block. */
    @SuppressWarnings("unchecked")
    public void recordFromAnthropicUsage(Map<String, Object> body, AiProvider provider,
                                         String operation, String modelName) {
        if (body == null) return;
        Object usageObj = body.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) return;
        record(provider, operation, modelName,
                intVal(usage.get("input_tokens")),
                intVal(usage.get("output_tokens")));
    }

    private BigDecimal estimateCost(AiProvider provider, int promptTokens, int completionTokens) {
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
