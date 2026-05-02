package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 분석의 진입점.
 * AiSettingRepository에서 현재 활성 설정을 읽어 적합한 클라이언트로 위임한다.
 *
 * 제공자 추가 방법:
 *  1. AiProvider enum에 항목 추가
 *  2. AiAnalysisClient 구현체 작성
 *  3. 이 클래스의 switch에 분기 추가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiSettingRepository aiSettingRepository;
    private final OpenAiClient openAiClient;
    private final AnthropicClient anthropicClient;

    // ── 공개 API ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String summarizeCve(String cveId, String severity, double cvssScore,
                               String cveType, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, cveType, component), setting);
            case ANTHROPIC -> anthropicClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, cveType, component), setting);
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
            case ANTHROPIC      -> anthropicClient.callWithSetting(prompt, setting);
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
            case ANTHROPIC      -> anthropicClient.callWithSetting(prompt, setting);
        };
    }

    /** AI가 설정되어 있는지 확인 (UI에서 안내 문구 표시용) */
    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().orElseGet(() -> {
            log.debug("[AI] 활성화된 AI 설정이 없습니다. 분석을 건너뜁니다.");
            return null;
        });
    }

    private String buildCvePrompt(String cveId, String severity, double cvssScore,
                                   String cveType, String component) {
        return String.format(
                "In one sentence, explain the risk of %s (severity: %s, CVSS: %.1f, type: %s) " +
                "found in %s for a developer who needs to understand the impact quickly.",
                cveId, severity, cvssScore, cveType, component);
    }
}
