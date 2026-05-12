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
 * Anthropic Messages API implementation (claude-3-5-sonnet, etc.).
 * AiAnalysisService delegates to this class after checking the provider type.
 */
@Slf4j
@Component
public class AnthropicClient implements AiAnalysisClient {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

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
                "across versions [%s]. In one sentence, give a concise risk insight.",
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

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting) {
        String apiKey = setting != null ? setting.getApiKey() : null;
        String model  = (setting != null && setting.getModelName() != null)
                        ? setting.getModelName() : "claude-3-5-sonnet-20241022";

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AI] Anthropic API key is not configured. Skipping.");
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 120,
                "system", "You are an expert software supply chain security analyst. Be concise.",
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var content = (List<?>) response.getBody().get("content");
                if (content != null && !content.isEmpty()) {
                    return (String) ((Map<?, ?>) content.get(0)).get("text");
                }
            }
        } catch (Exception e) {
            log.error("[AI] Anthropic call failed: {}", e.getMessage());
        }
        return null;
    }
}
