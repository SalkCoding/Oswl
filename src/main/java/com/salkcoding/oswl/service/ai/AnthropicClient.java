package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.ParameterizedTypeReference;

/**
 * Anthropic Messages API implementation (claude-opus, claude-sonnet, claude-haiku families).
 * AiAnalysisService delegates to this class after checking the provider type.
 *
 * <p>Note this client deliberately does not send {@code temperature}. Current Claude models
 * (Opus 4.7 and newer, Sonnet 5) reject sampling parameters outright with a 400, so forwarding
 * the configured value would break every modern model. Response style is steered through the
 * system prompt instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClient implements AiAnalysisClient {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1/models";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String PROVIDER_TAG = "Anthropic";
    /** Used only when a setting was saved without an explicit model. */
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5";

    private final AiPromptTemplateService promptTemplates;
    private final AiCallTrace callTrace;
    private final AiUsageRecorderService usageRecorder;
    private final RestTemplate restTemplate = AiRestTemplates.forCompletions();
    private final RestTemplate probeTemplate = AiRestTemplates.forProbe();

    /**
     * F4: mark the (per-provider fixed, C4-stable) system prompt as an ephemeral cache
     * breakpoint so repeat calls within Anthropic's cache TTL are billed as cache reads instead
     * of full input tokens. Below Anthropic's minimum cacheable block size the marker is simply
     * inert (no error, no charge) — see the F4 section of PERFORMANCE-AND-OFFLINE-PLAN.md for
     * why today's short system prompt may not clear that threshold on its own.
     */
    @Value("${oswl.ai.anthropic.prompt-caching-enabled:true}")
    private boolean promptCachingEnabled;

    @Override
    public String summarizeCve(String cveId, String severity, double cvssScore,
                               String cveType, String component) {
        return call(promptTemplates.cveSingleWithType(cveId, severity, cvssScore, cveType, component),
                null, "cve.single", null);
    }

    @Override
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        return call(promptTemplates.licenseSingle(licenseName, licenseStatus, component,
                null, "unknown", null), null, "license.single", null);
    }

    public String callWithSetting(String prompt, AiSetting setting) {
        return callWithSetting(prompt, setting, "completion");
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation) {
        return callWithSetting(prompt, setting, operation, null);
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation, String resolvedApiKey) {
        return call(prompt, setting, operation, resolvedApiKey);
    }

    /**
     * Free connection probe: {@code GET /v1/models} validates the key and reachability without
     * generating any tokens, so a connection test costs nothing and does not consume the daily
     * call cap.
     *
     * @return the model ids the account can access
     * @throws org.springframework.web.client.RestClientException on any transport or HTTP error,
     *         which {@link AiConnectionDiagnostics} turns into an actionable message
     */
    public List<String> probeModels(String resolvedApiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", resolvedApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        log.debug("[AI][{}] probe → GET {}", PROVIDER_TAG, ANTHROPIC_MODELS_URL);
        ResponseEntity<Map<String, Object>> response = probeTemplate.exchange(
                ANTHROPIC_MODELS_URL, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        Map<String, Object> body = response.getBody();
        if (body == null || !(body.get("data") instanceof List<?> data)) return List.of();
        return data.stream()
                .filter(Map.class::isInstance)
                .map(entry -> ((Map<?, ?>) entry).get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting, String operation, String resolvedApiKey) {
        // claude-3-5-sonnet-20241022 was the previous fallback and has since been retired —
        // it now 404s, so any setting saved without an explicit model silently failed.
        String model  = (setting != null && setting.getModelName() != null)
                        ? setting.getModelName() : DEFAULT_ANTHROPIC_MODEL;
        String op = operation != null ? operation : "completion";
        String detail = "model=" + model + " promptLen=" + userPrompt.length();

        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            log.warn("[AI][Anthropic] API key is not configured. Skipping.");
            return null;
        }

        callTrace.logPromptExcerpt(log, PROVIDER_TAG, op, userPrompt);
        log.debug("[AI][{}] → url='{}' model='{}' promptLen={}",
                PROVIDER_TAG, ANTHROPIC_URL, model, userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", resolvedApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        String systemPrompt = promptTemplates.getSystemPrompt(Objects.requireNonNull(setting).getProvider());
        // F4: system as a plain string is billed as full input tokens on every call. As a block
        // array with cache_control, an identical block within Anthropic's cache TTL is billed
        // as a (cheaper) cache read instead — the block is byte-identical across calls for a
        // given provider (getSystemPrompt returns a fixed per-provider string), so it is always
        // eligible to be a cache breakpoint.
        Object systemField = promptCachingEnabled
                ? List.of(Map.of("type", "text", "text", systemPrompt,
                        "cache_control", Map.of("type", "ephemeral")))
                : systemPrompt;
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "model", model,
                "max_tokens", promptTemplates.getMaxTokens(op, AiProvider.ANTHROPIC),
                "system", systemField,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        ));
        // Effort lives under output_config on the Messages API (not a top-level field, and not the
        // removed thinking.budget_tokens). Omitted entirely on DEFAULT: models older than the 4.5
        // family reject the parameter, so opting in has to be the user's choice.
        String effort = promptTemplates.getReasoningEffort().anthropicValue();
        if (effort != null) {
            body.put("output_config", Map.of("effort", effort));
        }

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AiCallTrace.Session _ = callTrace.begin(log, PROVIDER_TAG, op, detail + " attempt=" + attempt)) {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                        new ParameterizedTypeReference<>() {});

                long elapsed = System.currentTimeMillis() - start;
                log.debug("[AI][{}] ← status={} elapsedMs={} attempt={}", PROVIDER_TAG, response.getStatusCode(), elapsed, attempt);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    AiProvider providerTag = setting.getProvider();
                    usageRecorder.recordFromAnthropicUsage(response.getBody(), providerTag, op, model);
                    logAnthropicContentBlocks(op, response.getBody());
                    var content = (List<?>) response.getBody().get("content");
                    if (content != null && !content.isEmpty()) {
                        String result = extractText(content);
                        if (result != null) result = result.strip();
                        callTrace.logAssistantMessage(log, PROVIDER_TAG, op, result, null);
                        log.debug("[AI][{}] Parsed result resultLen={}", PROVIDER_TAG, result != null ? result.length() : 0);
                        if (result != null && !result.isBlank()) return result;
                        log.warn("[AI][{}] Empty result on attempt {} — giving up (retry is AiAnalysisService's responsibility)", PROVIDER_TAG, attempt);
                        return null;
                    }
                    log.warn("[AI][{}] Response body had no 'content' — keys={}", PROVIDER_TAG, response.getBody().keySet());
                    return null;
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if ((msg.contains("429") || msg.contains("RateLimitReached") || msg.contains("TooManyRequests") || msg.contains("rate_limit")) && attempt < 2) {
                    int waitSec = parseRateLimitWaitSeconds(msg);
                    log.warn("[AI][{}] Rate limited — waiting {}s before retry (attempt {})", PROVIDER_TAG, waitSec, attempt);
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                } else {
                    log.error("[AI][{}] Call failed after {}ms attempt={} — {}: {}", PROVIDER_TAG, elapsed, attempt, e.getClass().getSimpleName(), e.getMessage());
                    if ("test.connection".equals(op)) {
                        throw e;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Returns the answer text from the {@code content} block list.
     *
     * <p>Reading {@code content[0].text} is not safe: when the model reasons (which higher effort
     * levels make likely), block 0 is a {@code thinking} block whose {@code text} is null — the
     * call then looked like an empty response and the insight silently never appeared, even though
     * the API had answered. Pick the first {@code text} block instead, and concatenate the rest so
     * a split answer is not truncated.
     */
    private static String extractText(List<?> content) {
        StringBuilder sb = new StringBuilder();
        for (Object block : content) {
            if (!(block instanceof Map<?, ?> map)) continue;
            Object type = map.get("type");
            if (type != null && !"text".equals(type.toString())) continue;
            Object text = map.get("text");
            if (text instanceof String s && !s.isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(s);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void logAnthropicContentBlocks(String operation, Map<String, Object> body) {
        if (!log.isDebugEnabled()) return;
        Object raw = body.get("content");
        if (!(raw instanceof List<?> blocks)) return;
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> map)) continue;
            String type = map.get("type") != null ? map.get("type").toString() : "";
            if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                Object text = map.get("thinking");
                if (text == null) text = map.get("text");
                if (text != null && !text.toString().isBlank()) {
                    callTrace.logAssistantMessage(log, PROVIDER_TAG, operation + "/" + type,
                            text.toString(), Map.of("reasoning", text));
                }
            }
        }
    }

    private static int parseRateLimitWaitSeconds(String message) {
        if (message == null) return 30;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("wait (\\d+) second").matcher(message);
        return m.find() ? Math.min(Integer.parseInt(m.group(1)) + 2, 60) : 30;
    }
}
