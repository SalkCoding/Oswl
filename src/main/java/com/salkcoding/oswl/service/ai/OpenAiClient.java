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
 * OpenAI Chat Completions API implementation.
 * The LOCAL provider also uses this client when its baseUrl points to an OpenAI-compatible endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient implements AiAnalysisClient {

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String PROVIDER_TAG = "OpenAI";

    private final AiPromptTemplateService promptTemplates;
    private final AiCallTrace callTrace;
    private final RestTemplate restTemplate = new RestTemplate();

    // ── Called only within the package (delegated by AiAnalysisService) ───────────────

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

    /**
     * Calls the API using apiKey / baseUrl / modelName stored in AiSetting.
     * Executed with the setting injected by AiAnalysisService.
     */
    public String callWithSetting(String prompt, AiSetting setting) {
        return callWithSetting(prompt, setting, "completion");
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation) {
        return callWithSetting(prompt, setting, operation, null);
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation, String resolvedApiKey) {
        return call(prompt, setting, operation, resolvedApiKey);
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting, String operation, String resolvedApiKey) {
        String url   = resolveUrl(setting);
        String model = resolveModel(setting);
        String apiKey = resolvedApiKey;
        boolean hasAuth = apiKey != null && !apiKey.isBlank();
        String op = operation != null ? operation : "completion";
        String detail = "model=" + model + " promptLen=" + userPrompt.length();

        callTrace.logPromptExcerpt(log, PROVIDER_TAG, op, userPrompt);

        if (!hasAuth && DEFAULT_OPENAI_URL.equals(url)) {
            log.warn("[AI][{}] API key is not configured. Skipping.", PROVIDER_TAG);
            return null;
        }

        log.debug("[AI][{}] → url='{}' model='{}' auth={} promptLen={}",
                PROVIDER_TAG, url, model, hasAuth ? "Bearer" : "none", userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // LOCAL (for example, Ollama) does not require an API key — set Authorization only when a key exists
        if (hasAuth) {
            headers.setBearerAuth(apiKey);
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system",
                               "content", promptTemplates.getSystemPrompt()),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", promptTemplates.getMaxTokens(),
                "temperature", promptTemplates.getTemperature()
        );

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AiCallTrace.Session trace = callTrace.begin(log, PROVIDER_TAG, op, detail + " attempt=" + attempt)) {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(body, headers),
                        new ParameterizedTypeReference<>() {});

                long elapsed = System.currentTimeMillis() - start;
                log.debug("[AI][{}] ← status={} elapsedMs={} attempt={}", PROVIDER_TAG, response.getStatusCode(), elapsed, attempt);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    var choices = (List<?>) response.getBody().get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        var message = (Map<?, ?>) ((Map<?, ?>) choices.getFirst()).get("message");
                        String result = message != null ? (String) message.get("content") : null;
                        if (result != null) result = result.strip();
                        callTrace.logAssistantMessage(log, PROVIDER_TAG, op, result, message);
                        log.debug("[AI][{}] Parsed result resultLen={}", PROVIDER_TAG, result != null ? result.length() : 0);
                        if (result != null && !result.isBlank()) return result;
                        log.warn("[AI][{}] Empty result on attempt {}, {}", PROVIDER_TAG, attempt, attempt < 2 ? "retrying" : "giving up");
                    }
                    log.warn("[AI][{}] Response body has no 'choices' — keys={}", PROVIDER_TAG, response.getBody().keySet());
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if ((msg.contains("429") || msg.contains("RateLimitReached") || msg.contains("TooManyRequests")) && attempt < 2) {
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

    private static int parseRateLimitWaitSeconds(String message) {
        if (message == null) return 30;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("wait (\\d+) second").matcher(message);
        return m.find() ? Math.min(Integer.parseInt(m.group(1)) + 2, 60) : 30;
    }

    private String resolveUrl(AiSetting setting) {
        if (setting != null && setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            // LOCAL example: "http://localhost:11434/v1/chat/completions"
            String base = setting.getBaseUrl();
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        }
        return DEFAULT_OPENAI_URL;
    }

    private String resolveModel(AiSetting setting) {
        if (setting != null && setting.getModelName() != null && !setting.getModelName().isBlank()) {
            return setting.getModelName();
        }
        return "gpt-4o-mini";
    }
}
