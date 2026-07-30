package com.salkcoding.oswl.domain.enums;

/**
 * How hard the model should reason before answering.
 *
 * <p>Every provider spells this differently, so the enum carries the wire value each one expects
 * instead of leaving the mapping scattered across the clients:
 * <ul>
 *   <li>OpenAI / Gemini (OpenAI-compatible) / local runtimes → {@code reasoning_effort}, which
 *       only accepts {@code minimal|low|medium|high} — {@code XHIGH} and {@code MAX} therefore
 *       collapse to {@code high} rather than being rejected.</li>
 *   <li>Anthropic → {@code output_config.effort}, which accepts the full ladder including
 *       {@code xhigh} and {@code max}.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} sends nothing at all. That is deliberately the default: older models
 * (and most local runtimes) reject or ignore the parameter, so opting in has to be a choice.
 */
public enum AiEffort {

    /** Send no effort parameter — the provider's own default applies. */
    DEFAULT(null, null),
    /** Fastest and cheapest; fine for short triage notes. */
    LOW("low", "low"),
    MEDIUM("medium", "medium"),
    HIGH("high", "high"),
    /** Anthropic-only rung between high and max; other providers see {@code high}. */
    XHIGH("high", "xhigh"),
    /** Deepest reasoning, highest token spend. Other providers see {@code high}. */
    MAX("high", "max");

    private final String openAiValue;
    private final String anthropicValue;

    AiEffort(String openAiValue, String anthropicValue) {
        this.openAiValue = openAiValue;
        this.anthropicValue = anthropicValue;
    }

    /** Value for the OpenAI-compatible {@code reasoning_effort} field, or null when nothing should be sent. */
    public String openAiValue() {
        return openAiValue;
    }

    /** Value for Anthropic's {@code output_config.effort} field, or null when nothing should be sent. */
    public String anthropicValue() {
        return anthropicValue;
    }

    public boolean isDefault() {
        return this == DEFAULT;
    }

    /** Null-safe parse — unknown or missing values fall back to {@link #DEFAULT}. */
    public static AiEffort parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
