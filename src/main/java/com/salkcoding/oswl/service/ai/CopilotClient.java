package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/**
 * GitHub Copilot / GitHub Models API implementation.
 * Supports two endpoints:
 * <ul>
 *   <li>{@code https://api.githubcopilot.com/chat/completions} — Copilot internal API.
 *       Requires an OAuth user token obtained through the GitHub Copilot authorization flow.
 *       PATs are <b>not</b> accepted by this endpoint.</li>
 *   <li>{@code https://models.inference.ai.azure.com/chat/completions} — GitHub Models API.
 *       Accepts a GitHub PAT with {@code models:read} (or {@code models:inference}) scope.
 *       Use this endpoint when authenticating with a PAT.</li>
 * </ul>
 *
 * <p>If the configured base URL starts with {@code https://models.inference.ai.azure.com},
 * the PAT-compatible GitHub Models endpoint is used automatically.
 * Otherwise the default Copilot endpoint is used.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CopilotClient implements AiAnalysisClient {

    private static final String COPILOT_URL      = "https://api.githubcopilot.com/chat/completions";
    private static final String GITHUB_MODELS_URL = "https://models.inference.ai.azure.com/chat/completions";
    private static final String EDITOR_VERSION    = "OsWL/1.0";
    private static final String INTEGRATION_ID    = "OsWL";
    private static final String PROVIDER_TAG = "Copilot";

    private final AiPromptTemplateService promptTemplates;
    private final AiCallTrace callTrace;
    private final OutboundUrlValidator outboundUrlValidator;
    private final RestTemplate restTemplate = new RestTemplate();

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

    // ── Internal ─────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting, String operation, String resolvedApiKey) {
        String apiKey = resolvedApiKey;

        String url;
        if (setting != null && setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            String base = setting.getBaseUrl().trim();
            outboundUrlValidator.validateHttpUrl(base);
            url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        } else {
            url = GITHUB_MODELS_URL;
        }

        String model;
        if (setting != null && setting.getModelName() != null && !setting.getModelName().isBlank()) {
            model = setting.getModelName();
        } else {
            model = "gpt-4o-mini";
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AI][Copilot] GitHub token is not configured. Skipping.");
            return null;
        }

        String op = operation != null ? operation : "completion";
        String detail = "model=" + model + " promptLen=" + userPrompt.length();

        callTrace.logPromptExcerpt(log, PROVIDER_TAG, op, userPrompt);
        log.debug("[AI][{}] → url='{}' model='{}' promptLen={}", PROVIDER_TAG, url, model, userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Editor-Version", EDITOR_VERSION);
        headers.set("Copilot-Integration-Id", INTEGRATION_ID);

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
                        var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
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
}
