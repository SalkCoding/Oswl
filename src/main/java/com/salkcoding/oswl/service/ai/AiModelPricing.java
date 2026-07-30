package com.salkcoding.oswl.service.ai;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Published list prices per 1M tokens, keyed by model id.
 *
 * <p>Cost estimation used to apply one flat rate per provider, which is off by an order of
 * magnitude across a provider's own line-up (a Haiku call and an Opus call are not the same
 * price). Lookup is longest-prefix so dated or suffixed ids resolve to their family — e.g.
 * {@code gemini-3.1-pro-preview} matches {@code gemini-3.1-pro}, and {@code gpt-5.4-mini}
 * matches its own entry rather than the shorter {@code gpt-5.4}.
 *
 * <p>Unknown models fall through to the configurable per-provider defaults in
 * {@link AiUsageRecorderService}, so a custom or self-hosted model still gets an estimate.
 * These are list prices only — they ignore cached-input discounts, batch discounts and
 * long-context tiers, so the figures stay estimates and never replace a provider invoice.
 */
public final class AiModelPricing {

    /** Input/output list price in USD per 1,000,000 tokens. */
    public record Rate(double inputPer1M, double outputPer1M) {}

    /**
     * Insertion order is irrelevant — lookup picks the longest matching key — but entries are
     * grouped by provider and ordered newest-first to keep the table readable.
     */
    private static final Map<String, Rate> RATES = new LinkedHashMap<>();

    static {
        // ── OpenAI ──────────────────────────────────────────────────────────
        RATES.put("gpt-5.6-sol",   new Rate(5.00, 30.00));
        RATES.put("gpt-5.6-terra", new Rate(2.50, 15.00));
        RATES.put("gpt-5.6-luna",  new Rate(1.00,  6.00));
        RATES.put("gpt-5.5-pro",   new Rate(30.00, 180.00));
        RATES.put("gpt-5.5",       new Rate(5.00, 30.00));
        RATES.put("gpt-5.4-pro",   new Rate(30.00, 180.00));
        RATES.put("gpt-5.4-mini",  new Rate(0.75,  4.50));
        RATES.put("gpt-5.4-nano",  new Rate(0.20,  1.25));
        RATES.put("gpt-5.4",       new Rate(2.50, 15.00));
        RATES.put("gpt-4o-mini",   new Rate(0.15,  0.60));
        RATES.put("o3-mini",       new Rate(1.10,  4.40));
        RATES.put("o3",            new Rate(2.00,  8.00));

        // ── Anthropic ───────────────────────────────────────────────────────
        RATES.put("claude-fable-5",     new Rate(10.00, 50.00));
        RATES.put("claude-mythos-5",    new Rate(10.00, 50.00));
        RATES.put("claude-opus-5",      new Rate(5.00, 25.00));
        RATES.put("claude-opus-4-8",    new Rate(5.00, 25.00));
        RATES.put("claude-opus-4-7",    new Rate(5.00, 25.00));
        RATES.put("claude-opus-4-6",    new Rate(5.00, 25.00));
        RATES.put("claude-sonnet-5",    new Rate(3.00, 15.00));
        RATES.put("claude-sonnet-4-6",  new Rate(3.00, 15.00));
        RATES.put("claude-haiku-4-5",   new Rate(1.00,  5.00));

        // ── Google Gemini ───────────────────────────────────────────────────
        RATES.put("gemini-3.1-flash-lite", new Rate(0.25,  1.50));
        RATES.put("gemini-3.1-pro",        new Rate(2.00, 12.00));
        RATES.put("gemini-3.6-flash",      new Rate(1.50,  7.50));
        RATES.put("gemini-3.5-flash",      new Rate(1.50,  9.00));
        RATES.put("gemini-3-flash",        new Rate(0.50,  3.00));
        RATES.put("gemini-3-pro",          new Rate(2.00, 12.00));
        RATES.put("gemini-2.5-flash-lite", new Rate(0.10,  0.40));
        RATES.put("gemini-2.5-flash",      new Rate(0.30,  2.50));
        RATES.put("gemini-2.5-pro",        new Rate(1.25, 10.00));
    }

    private AiModelPricing() {}

    /**
     * Resolves the list price for a model id, matching the longest known prefix so that
     * suffixed variants ({@code -preview}, {@code -latest}, a date stamp) resolve to their
     * family. Returns empty for a blank or unrecognised id — including every locally hosted
     * model, which has no list price at all.
     */
    public static Optional<Rate> rateFor(String modelName) {
        if (modelName == null || modelName.isBlank()) return Optional.empty();
        String normalized = modelName.strip().toLowerCase();

        String bestKey = null;
        for (String key : RATES.keySet()) {
            if (normalized.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return Optional.ofNullable(bestKey).map(RATES::get);
    }

    /** Estimated USD cost for a call, or empty when the model has no published rate. */
    public static Optional<BigDecimal> estimate(String modelName, int promptTokens, int completionTokens) {
        return rateFor(modelName).map(rate -> BigDecimal.valueOf(
                (promptTokens / 1_000_000.0) * rate.inputPer1M()
                        + (completionTokens / 1_000_000.0) * rate.outputPer1M()));
    }
}
