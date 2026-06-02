package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Anthropic Messages API implementation (claude-3-5-sonnet, etc.).
 * AiAnalysisService delegates to this class after checking the provider type.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClient implements AiAnalysisClient {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String PROVIDER_TAG = "Anthropic";

    private final AiPromptTemplateService promptTemplates;
    private final AiCallTrace callTrace;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String summarizeCve(String cveId, String severity, double cvssScore,
                               String cveType, String component) {
        return call(promptTemplates.cveSingleWithType(cveId, severity, cvssScore, cveType, component), null, "cve.single");
    }

    @Override
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        return call(promptTemplates.licenseSingle(licenseName, licenseStatus, component,
                null, "unknown", null), null, "license.single");
    }

    public String callWithSetting(String prompt, AiSetting setting) {
        return callWithSetting(prompt, setting, "completion");
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation) {
        return call(prompt, setting, operation);
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting, String operation) {
        String apiKey = setting != null ? setting.getApiKey() : null;
        String model  = (setting != null && setting.getModelName() != null)
                        ? setting.getModelName() : "claude-3-5-sonnet-20241022";
        String op = operation != null ? operation : "completion";
        String detail = "model=" + model + " promptLen=" + userPrompt.length();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AI][Anthropic] API key is not configured. Skipping.");
            return null;
        }

        callTrace.logPromptExcerpt(log, PROVIDER_TAG, op, userPrompt);
        log.debug("[AI][{}] → url='{}' model='{}' promptLen={}",
                PROVIDER_TAG, ANTHROPIC_URL, model, userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", promptTemplates.getMaxTokens(),
                "system", promptTemplates.getSystemPrompt(),
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AiCallTrace.Session trace = callTrace.begin(log, PROVIDER_TAG, op, detail + " attempt=" + attempt)) {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                        new ParameterizedTypeReference<>() {});

                long elapsed = System.currentTimeMillis() - start;
                log.debug("[AI][{}] ← status={} elapsedMs={} attempt={}", PROVIDER_TAG, response.getStatusCode(), elapsed, attempt);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    logAnthropicContentBlocks(op, response.getBody());
                    var content = (List<?>) response.getBody().get("content");
                    if (content != null && !content.isEmpty()) {
                        String result = (String) ((Map<?, ?>) content.getFirst()).get("text");
                        if (result != null) result = result.strip();
                        callTrace.logAssistantMessage(log, PROVIDER_TAG, op, result, null);
                        log.debug("[AI][{}] Parsed result resultLen={}", PROVIDER_TAG, result != null ? result.length() : 0);
                        if (result != null && !result.isBlank()) return result;
                        log.warn("[AI][{}] Empty result on attempt {}, {}", PROVIDER_TAG, attempt, attempt < 2 ? "retrying" : "giving up");
                    }
                    log.warn("[AI][{}] Response body had no 'content' — keys={}", PROVIDER_TAG, response.getBody().keySet());
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
                    break;
                }
            }
        }
        return null;
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
