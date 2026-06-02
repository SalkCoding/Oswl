package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiSettingRepository aiSettingRepository;
    private final OpenAiClient openAiClient;
    private final AnthropicClient anthropicClient;
    private final CopilotClient copilotClient;
    private final EncryptionService encryptionService;
    private final AiPromptTemplateService promptTemplates;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record CveSummaryRequest(
            String id, String severity, double cvssScore, String component,
            String title, String osvSummary, String fixVersion, String cweId,
            String cvssVector, String dependencyType, String patchability) {}

    public record LicenseSummaryRequest(
            String id, String licenseName, String licenseStatus, String component,
            String ecosystem, String dependencyType, String latestVersion) {}

    @Transactional(readOnly = true)
    public String summarizeCve(String cveId, String severity, double cvssScore, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.cveSingle(cveId, severity, cvssScore, component);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public String generateSecurityTrendInsight(String projectName, int secDelta,
                                               String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityTrend(projectName, secDelta, recentVersions, changeDetails);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public String generateLicenseTrendInsight(String projectName, int licDelta,
                                              String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.licenseTrend(projectName, licDelta, recentVersions, changeDetails);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        return summarizeLicenseRisk(licenseName, licenseStatus, component, null, "unknown", null);
    }

    @Transactional(readOnly = true)
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component,
                                       String ecosystem, String dependencyType, String latestVersion) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.licenseSingle(licenseName, licenseStatus, component,
                ecosystem, dependencyType, latestVersion);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public String summarizeSecurityPosture(String projectName, AiEnrichmentContextBuilder.PostureContext ctx) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityPosture(ctx, projectName);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public String summarizeVersionDiff(String projectName, String fromVersion, String toVersion,
                                       int added, int removed, int updated, int newThreats,
                                       String threatDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.versionDiff(projectName, fromVersion, toVersion,
                added, removed, updated, newThreats, threatDetails);
        return delegate(prompt, setting);
    }

    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    public boolean testConnection(AiSetting setting) {
        String prompt = promptTemplates.testConnection();
        try {
            String result = delegate(prompt, setting);
            return result != null;
        } catch (Exception e) {
            log.warn("[AI] {} provider connection test failed: {}", setting.getProvider(), e.getMessage());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, String> batchSummarizeCves(List<CveSummaryRequest> items) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        String prompt = promptTemplates.batchCvePrompt(items);
        return batchWithRetry(prompt, setting, items.size(), "CVE");
    }

    @Transactional(readOnly = true)
    public Map<String, String> batchSummarizeLicenses(List<LicenseSummaryRequest> items) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        String prompt = promptTemplates.batchLicensePrompt(items);
        return batchWithRetry(prompt, setting, items.size(), "license");
    }

    private Map<String, String> batchWithRetry(String prompt, AiSetting setting, int itemCount, String label) {
        try {
            Map<String, String> result = parseBatchSummaryResponse(delegate(prompt, setting));
            if (!result.isEmpty()) return result;
            log.warn("[AI] Batch {} summary empty ({} items) — retrying once", label, itemCount);
            return parseBatchSummaryResponse(delegate(prompt, setting));
        } catch (Exception e) {
            log.warn("[AI] Batch {} summary failed: {} — retrying once", label, e.getMessage());
            try {
                return parseBatchSummaryResponse(delegate(prompt, setting));
            } catch (Exception retryEx) {
                log.warn("[AI] Batch {} summary retry failed: {}", label, retryEx.getMessage());
                return Map.of();
            }
        }
    }

    private String delegate(String prompt, AiSetting setting) {
        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting);
            case COPILOT               -> copilotClient.callWithSetting(prompt, setting);
        };
    }

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().map(s -> {
            if (s.getApiKey() != null && !s.getApiKey().isBlank()) {
                try {
                    s.update(encryptionService.decrypt(s.getApiKey()), null, null);
                } catch (Exception e) {
                    log.warn("[AI] Failed to decrypt API key for {} provider.", s.getProvider());
                }
            }
            return s;
        }).orElseGet(() -> {
            log.debug("[AI] No active AI setting. Skipping analysis.");
            return null;
        });
    }

    private Map<String, String> parseBatchSummaryResponse(String response) {
        if (response == null || response.isBlank()) return Map.of();
        try {
            int start = response.indexOf('[');
            int end   = response.lastIndexOf(']');
            if (start < 0 || end <= start) return Map.of();
            String json = response.substring(start, end + 1);
            List<Map<String, String>> list = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, String> entry : list) {
                String id = entry.get("id");
                String formatted = AiStructuredSummary.formatForDisplay(entry);
                if (id != null && formatted != null) {
                    result.put(id.strip(), formatted);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[AI] Failed to parse batch response: {} — raw='{}'", e.getMessage(),
                    response.length() > 200 ? response.substring(0, 200) : response);
            return Map.of();
        }
    }
}
