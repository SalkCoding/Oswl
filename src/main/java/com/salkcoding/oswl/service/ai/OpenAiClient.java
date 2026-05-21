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
 * OpenAI Chat Completions API 구현체.
 * LOCAL 프로바이더도 baseUrl을 OpenAI 호환 엔드포인트로 변경하면 이 클라이언트로 동작한다.
 */
@Slf4j
@Component
public class OpenAiClient implements AiAnalysisClient {

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final RestTemplate restTemplate;

    public OpenAiClient() {
        this.restTemplate = new RestTemplate();
    }

    // ── Called only within the package (delegated by AiAnalysisService) ───────────────

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

    /**
     * AiSetting에 저장된 apiKey / baseUrl / modelName을 사용하여 호출한다.
     * AiAnalysisService가 주입한 설정과 함께 실행된다.
     */
    public String callWithSetting(String prompt, AiSetting setting) {
        return call(prompt, setting);
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting) {
        String url   = resolveUrl(setting);
        String model = resolveModel(setting);
        String apiKey = setting != null ? setting.getApiKey() : null;
        boolean hasAuth = apiKey != null && !apiKey.isBlank();

        log.debug("[AI][OpenAI] → url='{}' model='{}' auth={} promptLen={}",
                url, model, hasAuth ? "Bearer" : "none", userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // LOCAL(예: Ollama)는 API 키가 필요하지 않음 — 키가 있을 때만 Authorization 설정
        if (hasAuth) {
            headers.setBearerAuth(apiKey);
        }

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
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {});

            long elapsed = System.currentTimeMillis() - start;
            log.debug("[AI][OpenAI] ← status={} elapsedMs={}", response.getStatusCode(), elapsed);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var choices = (List<?>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
                    String result = message != null ? (String) message.get("content") : null;
                    log.debug("[AI][OpenAI] 파싱 결과 resultLen={}", result != null ? result.length() : 0);
                    return result;
                }
                log.warn("[AI][OpenAI] 응답 본문에 'choices'가 없음 — keys={}", response.getBody().keySet());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[AI][OpenAI] {}ms 후 호출 실패 — {}: {}", elapsed, e.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    private String resolveUrl(AiSetting setting) {
        if (setting != null && setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            // LOCAL: 예: "http://localhost:11434/v1/chat/completions"
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
