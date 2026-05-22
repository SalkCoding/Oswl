package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
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
 *  3. Add a branch to this class's switch statement
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

    // ── Public API ─────────────────────────────────────────────────────

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

    /** Checks whether an AI provider is configured (used for UI guidance). */
    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    /**
     * Sends a minimal ping prompt to the provider using the supplied plaintext setting.
     * Not persisted — used only by the Settings > AI > Test Connection button.
     *
     * @return true if the provider responds successfully; otherwise false
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
            log.debug("[AI] testConnection result={} response='{}'", ok ? "OK" : "FAIL",
                    result != null ? result.trim() : "null");
            return ok;
        } catch (Exception e) {
            log.warn("[AI] {} provider connection test failed: {}", setting.getProvider(), e.getMessage());
            return false;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().map(s -> {
            // Decrypt the stored API key before passing to AI clients
            if (s.getApiKey() != null && !s.getApiKey().isBlank()) {
                try {
                    s.update(encryptionService.decrypt(s.getApiKey()), null, null);
                } catch (Exception e) {
                    // Backward compatibility: if decryption fails, the key may be legacy plaintext.
                    // Re-save the setting in Settings to encrypt it.
                    log.warn("[AI] Failed to decrypt API key for {} provider. The key may be stored as legacy plaintext. Re-save it in Settings to encrypt it.", s.getProvider());
                }
            }
            log.debug("[AI] Active provider={} model='{}' baseUrl='{}'",
                    s.getProvider(), s.getModelName(), s.getBaseUrl());
            return s;
        }).orElseGet(() -> {
            log.debug("[AI] No active AI setting. Skipping analysis.");
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
