package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for AI analysis.
 * Reads the currently active setting from AiSettingRepository and delegates to the appropriate client.
 *
 * To add a new provider:
 *  1. Add an entry to the AiProvider enum
 *  2. Implement the AiAnalysisClient interface
 *  3. Add a branch to the switch statement in this class
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiSettingRepository aiSettingRepository;
    private final OpenAiClient openAiClient;
    private final AnthropicClient anthropicClient;

    // ── Public API ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String summarizeCve(String cveId, String severity, double cvssScore, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;

        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(
                    buildCvePrompt(cveId, severity, cvssScore, component), setting);
            case ANTHROPIC -> anthropicClient.callWithSetting(
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

    /** Check whether an AI provider is configured (for displaying guidance in the UI) */
    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().orElseGet(() -> {
            log.debug("[AI] No active AI setting found. Skipping analysis.");
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
