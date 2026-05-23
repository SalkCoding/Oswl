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
 * GitHub Copilot Chat API implementation.
 * Uses the OpenAI-compatible chat completions endpoint provided by GitHub Copilot.
 * Authentication is performed with a GitHub PAT (personal access token) or a
 * GitHub Copilot token that has the {@code copilot} scope.
 *
 * <p>API endpoint: {@code https://api.githubcopilot.com/chat/completions}
 */
@Slf4j
@Component
public class CopilotClient implements AiAnalysisClient {

    private static final String COPILOT_URL   = "https://api.githubcopilot.com/chat/completions";
    private static final String EDITOR_VERSION = "OsWL/1.0";
    private static final String INTEGRATION_ID = "OsWL";

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
        String model  = (setting != null && setting.getModelName() != null && !setting.getModelName().isBlank())
                        ? setting.getModelName() : "gpt-4o";

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AI][Copilot] GitHub token is not configured. Skipping.");
            return null;
        }

        log.debug("[AI][Copilot] → url='{}' model='{}' promptLen={}", COPILOT_URL, model, userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Editor-Version", EDITOR_VERSION);
        headers.set("Copilot-Integration-Id", INTEGRATION_ID);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system",
                               "content", "You are an expert software supply chain security analyst. Be concise."),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", 120,
                "temperature", 0.3
        );

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    COPILOT_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {});

            long elapsed = System.currentTimeMillis() - start;
            log.debug("[AI][Copilot] ← status={} elapsedMs={}", response.getStatusCode(), elapsed);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var choices = (List<?>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
                    String result = message != null ? (String) message.get("content") : null;
                    log.debug("[AI][Copilot] Parsed result resultLen={}", result != null ? result.length() : 0);
                    return result;
                }
                log.warn("[AI][Copilot] Response body has no 'choices' — keys={}", response.getBody().keySet());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[AI][Copilot] Call failed after {}ms — {}: {}", elapsed, e.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }
}
