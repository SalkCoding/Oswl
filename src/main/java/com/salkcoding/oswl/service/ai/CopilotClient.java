package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
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
public class CopilotClient implements AiAnalysisClient {

    private static final String COPILOT_URL      = "https://api.githubcopilot.com/chat/completions";
    private static final String GITHUB_MODELS_URL = "https://models.inference.ai.azure.com/chat/completions";
    private static final String EDITOR_VERSION    = "OsWL/1.0";
    private static final String INTEGRATION_ID    = "OsWL";

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String summarizeCve(String cveId, String severity, double cvssScore,
                               String cveType, String component) {
        String prompt = String.format(
                "In one sentence, explain the risk of %s (severity: %s, CVSS: %.1f, type: %s) " +
                "found in %s for a developer who needs to understand the impact quickly.",
                cveId, severity, cvssScore, cveType, component);
        return call(prompt, null);
    }

    @Override
    public String generateRiskInsight(String projectName, int securityDelta,
                                      int licenseDelta, String recentVersions) {
        String prompt = String.format(
                "Project '%s' shows security issues %s by %d and license issues %s by %d " +
                "across versions [%s]. In one sentence, give a concise risk insight for a security engineer.",
                projectName,
                securityDelta >= 0 ? "increased" : "decreased", Math.abs(securityDelta),
                licenseDelta >= 0 ? "increased" : "decreased", Math.abs(licenseDelta),
                recentVersions);
        return call(prompt, null);
    }

    @Override
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        String prompt = String.format(
                "In one sentence, explain the compliance risk of using '%s' (status: %s) " +
                "in a commercial product component '%s'.",
                licenseName, licenseStatus, component);
        return call(prompt, null);
    }

    public String callWithSetting(String prompt, AiSetting setting) {
        return call(prompt, setting);
    }

    // ── Internal ─────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting) {
        String apiKey = setting != null ? setting.getApiKey() : null;

        // Determine endpoint: custom base URL overrides the default.
        // Default is GitHub Models (PAT-compatible). To use the internal Copilot
        // endpoint (api.githubcopilot.com), set Base URL explicitly — that endpoint
        // requires an OAuth user token, not a PAT.
        String url;
        if (setting != null && setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            String base = setting.getBaseUrl().trim();
            url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        } else {
            url = GITHUB_MODELS_URL;
        }

        boolean isGitHubModels = url.startsWith("https://models.inference.ai.azure.com");

        // Default model differs between the two endpoints
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

        log.debug("[AI][Copilot] → url='{}' model='{}' promptLen={}", url, model, userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        // Copilot-specific headers are harmless on GitHub Models too
        headers.set("Editor-Version", EDITOR_VERSION);
        headers.set("Copilot-Integration-Id", INTEGRATION_ID);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system",
                               "content", "You are an expert software supply chain security analyst. Be concise."),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", 800,
                "temperature", 0.3
        );

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {});

            long elapsed = System.currentTimeMillis() - start;
            log.debug("[AI][Copilot] ← status={} elapsedMs={} attempt={}", response.getStatusCode(), elapsed, attempt);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var choices = (List<?>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
                    String result = message != null ? (String) message.get("content") : null;
                    if (result != null) result = result.strip();
                    log.debug("[AI][Copilot] Parsed result resultLen={}", result != null ? result.length() : 0);
                    if (result != null && !result.isBlank()) return result;
                    log.warn("[AI][Copilot] Empty result on attempt {}, {}", attempt, attempt < 2 ? "retrying" : "giving up");
                }
                log.warn("[AI][Copilot] Response body has no 'choices' — keys={}", response.getBody().keySet());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if ((msg.contains("429") || msg.contains("RateLimitReached") || msg.contains("TooManyRequests")) && attempt < 2) {
                int waitSec = parseRateLimitWaitSeconds(msg);
                log.warn("[AI][Copilot] Rate limited — waiting {}s before retry (attempt {})", waitSec, attempt);
                try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            } else {
                log.error("[AI][Copilot] Call failed after {}ms attempt={} — {}: {}", elapsed, attempt, e.getClass().getSimpleName(), e.getMessage());
                break;
            }
        }
        } // end retry loop
        return null;
    }

    private static int parseRateLimitWaitSeconds(String message) {
        if (message == null) return 30;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("wait (\\d+) second").matcher(message);
        return m.find() ? Math.min(Integer.parseInt(m.group(1)) + 2, 60) : 30;
    }
}
