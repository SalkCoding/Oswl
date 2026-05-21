package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 분석의 진입점.
 * AiSettingRepository에서 현재 활성 설정을 읽고 적절한 클라이언트에 위임한다.
 *
 * 새 프로바이더 추가 방법:
 *  1. AiProvider 열거형에 항목 추가
 *  2. AiAnalysisClient 인터페이스 구현
 *  3. 이 클래스의 switch 문에 분기 추가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiSettingRepository aiSettingRepository;
    private final OpenAiClient openAiClient;
    private final AnthropicClient anthropicClient;
    private final CopilotClient copilotClient;
    private final EncryptionService encryptionService;

    // ── 공개 API ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String summarizeCve(String cveId, String severity, double cvssScore, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        log.debug("[AI] summarizeCve cveId='{}' severity={} cvss={} component='{}' provider={}",
                cveId, severity, cvssScore, component, setting.getProvider());
        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, component), setting);
            case ANTHROPIC -> anthropicClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, component), setting);
            case COPILOT   -> copilotClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, component), setting);
        };
    }

    @Transactional(readOnly = true)
    public String generateRiskInsight(String projectName, int securityDelta,
                                      int licenseDelta, String recentVersions) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;

        String prompt = String.format(
                "Project '%s' shows security issues %s by %d and license issues %s by %d " +
                "across versions [%s]. In one sentence, give a concise risk insight for a security engineer.",
                projectName,
                securityDelta >= 0 ? "increased" : "decreased", Math.abs(securityDelta),
                licenseDelta >= 0 ? "increased" : "decreased", Math.abs(licenseDelta),
                recentVersions);

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    @Transactional(readOnly = true)
    public String generateSecurityTrendInsight(String projectName, int secDelta, String recentVersions) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        log.debug("[AI] generateSecurityTrendInsight project='{}' secDelta={} versions='{}' provider={}",
                projectName, secDelta, recentVersions, setting.getProvider());
        String prompt = String.format(
                "Project '%s' has security issues %s by %d across versions [%s]. " +
                "In one sentence, give a concise security risk trend insight for a security engineer.",
                projectName,
                secDelta >= 0 ? "increased" : "decreased", Math.abs(secDelta),
                recentVersions);

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    @Transactional(readOnly = true)
    public String generateLicenseTrendInsight(String projectName, int licDelta, String recentVersions) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        log.debug("[AI] generateLicenseTrendInsight project='{}' licDelta={} versions='{}' provider={}",
                projectName, licDelta, recentVersions, setting.getProvider());
        String prompt = String.format(
                "Project '%s' has license issues %s by %d across versions [%s]. " +
                "In one sentence, give a concise license compliance trend insight for a security engineer.",
                projectName,
                licDelta >= 0 ? "increased" : "decreased", Math.abs(licDelta),
                recentVersions);

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    @Transactional(readOnly = true)
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;

        String prompt = String.format(
                "In one sentence, explain the compliance risk of using '%s' (status: %s) " +
                "in a commercial product component '%s'.",
                licenseName, licenseStatus, component);

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    @Transactional(readOnly = true)
    public String summarizeSecurityPosture(String projectName, int critical, int high, int totalComponents) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        log.debug("[AI] summarizeSecurityPosture project='{}' critical={} high={} total={} provider={}",
                projectName, critical, high, totalComponents, setting.getProvider());
        String prompt = String.format(
                "Project '%s' has %d components with %d critical and %d high severity vulnerabilities. " +
                "In one sentence, give a concise security posture summary for a security engineer.",
                projectName, totalComponents, critical, high);
        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    @Transactional(readOnly = true)
    public String summarizeVersionDiff(String projectName, String fromVersion, String toVersion,
                                        int added, int removed, int updated, int newThreats) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        log.debug("[AI] summarizeVersionDiff project='{}' from='{}' to='{}' added={} removed={} updated={} newThreats={} provider={}",
                projectName, fromVersion, toVersion, added, removed, updated, newThreats, setting.getProvider());
        String prompt = String.format(
                "Project '%s' changed from version '%s' to '%s': %d components added, %d removed, " +
                "%d updated, %d new threats introduced. " +
                "In one sentence, summarise the security impact of this version change.",
                projectName, fromVersion, toVersion, added, removed, updated, newThreats);
        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    /** AI 프로바이더가 설정되어 있는지 확인한다 (UI에 안내용 표시용) */
    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    /**
     * 주어진 졌팅(plaintext)으로 제시된 설정으로 프로바이더에 미니말 핑 프롬프트를 전송한다.
     * 영속성 없음 — Settings > AI > 연결 테스트 버튼 전용.
     *
     * @return 프로바이더가 성공적으로 응답하면 true, 그렇지 않으면 false
     */
    public boolean testConnection(AiSetting setting) {
        String prompt = "Reply with only the word OK.";
        log.debug("[AI] testConnection provider={} model='{}' baseUrl='{}'",
                setting.getProvider(), setting.getModelName(), setting.getBaseUrl());
        try {
            String result = switch (setting.getProvider()) {
                case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
                case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
                case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
            };
            boolean ok = result != null;
            log.debug("[AI] testConnection 결과={} 응답='{}'", ok ? "OK" : "FAIL",
                    result != null ? result.trim() : "null");
            return ok;
        } catch (Exception e) {
            log.warn("[AI] {} 프로바이더 연결 테스트 실패: {}", setting.getProvider(), e.getMessage());
            return false;
        }
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().map(s -> {
            // Decrypt the stored API key before passing to AI clients
            if (s.getApiKey() != null && !s.getApiKey().isBlank()) {
                try {
                    s.update(encryptionService.decrypt(s.getApiKey()), null, null);
                } catch (Exception e) {
                    // 하위 호환성: 복호화 실패 시 키가 레거시 평문텍스트일 수 있음.
                    // Settings에서 설정을 재저장하여 암호화하세요.
                    log.warn("[AI] {} 프로바이더 API 키 복호화 실패. 키가 레거시 평문텍스트로 저장되어 있을 수 있음. Settings에서 재저장하여 암호화하세요.", s.getProvider());
                }
            }
            log.debug("[AI] Active provider={} model='{}' baseUrl='{}'",
                    s.getProvider(), s.getModelName(), s.getBaseUrl());
            return s;
        }).orElseGet(() -> {
            log.debug("[AI] 활성 AI 설정 없음. 분석 건너덗.");
            return null;
        });
    }

    private String buildCvePrompt(String cveId, String severity, double cvssScore, String component) {
        return String.format(
                "In one sentence, explain the risk of %s (severity: %s, CVSS: %.1f) " +
                "found in %s for a developer who needs to understand the impact quickly.",
                cveId, severity, cvssScore, component);
    }
}
