package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.exception.AiSummaryFailureReason;
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
    private final EncryptionService encryptionService;
    private final AiPromptTemplateService promptTemplates;
    private final AiUsageLimiterService usageLimiter;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record CveSummaryRequest(
            String id, String severity, double cvssScore, String component,
            String title, String osvSummary, String fixVersion, String cweId,
            String cvssVector, String dependencyType, String patchability,
            Double epssScore, boolean kevListed) {}

    public record LicenseSummaryRequest(
            String id, String licenseName, String licenseStatus, String policyReason,
            String component, String ecosystem, String dependencyType, String latestVersion) {}

    public record CveSummarizeOutcome(
            AiStructuredSummary.ParsedEntry entry,
            AiSummaryFailureReason failure,
            Object[] failureArgs) {

        public static CveSummarizeOutcome ok(AiStructuredSummary.ParsedEntry entry) {
            return new CveSummarizeOutcome(entry, null, null);
        }

        public static CveSummarizeOutcome fail(AiSummaryFailureReason reason, Object... args) {
            return new CveSummarizeOutcome(null, reason, args);
        }

        public boolean success() {
            return entry != null;
        }
    }

    @Transactional(readOnly = true)
    public String summarizeCve(String cveId, String severity, double cvssScore, String component) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.cveSingle(cveId, severity, cvssScore, component);
        return delegate(prompt, setting, "cve.single");
    }

    @Transactional(readOnly = true)
    public String generateSecurityTrendInsight(String projectName, int secDelta,
                                               String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityTrend(projectName, secDelta, recentVersions, changeDetails);
        return delegate(prompt, setting, "security.trend");
    }

    @Transactional(readOnly = true)
    public String generateLicenseTrendInsight(String projectName, int licDelta,
                                              String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.licenseTrend(projectName, licDelta, recentVersions, changeDetails);
        return delegate(prompt, setting, "license.trend");
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
        return delegate(prompt, setting, "license.single");
    }

    @Transactional(readOnly = true)
    public String summarizeSecurityPosture(String projectName, AiEnrichmentContextBuilder.PostureContext ctx) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityPosture(ctx, projectName);
        return delegate(prompt, setting, "security.posture");
    }

    @Transactional(readOnly = true)
    public String summarizeVersionDiff(String projectName, String fromVersion, String toVersion,
                                       int added, int removed, int updated, int newThreats,
                                       String threatDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.versionDiff(projectName, fromVersion, toVersion,
                added, removed, updated, newThreats, threatDetails);
        return delegate(prompt, setting, "version.diff");
    }

    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    public boolean testConnection(AiSetting setting) {
        String prompt = promptTemplates.testConnection();
        try {
            // Caller (e.g. test-connection endpoint) supplies a plaintext key on the setting DTO.
            String result = delegate(prompt, setting, "test.connection", setting.getApiKey());
            if (result != null) {
                log.info("[AI] {} provider connection test succeeded", setting.getProvider());
                return true;
            }
            log.warn("[AI] {} provider connection test returned empty response", setting.getProvider());
            return false;
        } catch (Exception e) {
            log.warn("[AI] {} provider connection test failed: {}", setting.getProvider(), e.getMessage());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, AiStructuredSummary.ParsedEntry> batchSummarizeCves(
            List<CveSummaryRequest> items, String deploymentProfile) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        log.debug("[AI] batch.cve start — {} item(s), provider={}", items.size(), setting.getProvider());
        String prompt = promptTemplates.batchCvePrompt(items, deploymentProfile);
        return batchStructuredWithRetry(prompt, setting, items.size(), "CVE", "batch.cve");
    }

    @Transactional(readOnly = true)
    public AiStructuredSummary.ParsedEntry summarizeCveStructured(CveSummaryRequest item, String deploymentProfile) {
        CveSummarizeOutcome outcome = summarizeCveWithOutcome(item, deploymentProfile);
        return outcome.entry();
    }

    /**
     * On-demand CVE triage with an explicit failure reason (used by component-detail refresh).
     */
    @Transactional(readOnly = true)
    public CveSummarizeOutcome summarizeCveWithOutcome(CveSummaryRequest item, String deploymentProfile) {
        AiSetting setting = getActiveSetting();
        if (setting == null) {
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.NOT_CONFIGURED);
        }
        if (isApiKeyUnavailable(setting)) {
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.API_KEY_INVALID);
        }
        if (usageLimiter.isCapReached(setting.getProvider())) {
            return CveSummarizeOutcome.fail(
                    AiSummaryFailureReason.DAILY_CAP,
                    usageLimiter.getTodayCount(setting.getProvider()),
                    usageLimiter.getDailyCallCap());
        }

        String prompt = promptTemplates.batchCvePrompt(List.of(item), deploymentProfile);
        String response;
        try {
            response = delegate(prompt, setting, "batch.cve");
        } catch (Exception e) {
            log.warn("[AI] On-demand CVE summarize failed: {}", e.getMessage());
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.PROVIDER_ERROR);
        }
        if (response == null || response.isBlank()) {
            if (isApiKeyUnavailable(setting)) {
                return CveSummarizeOutcome.fail(AiSummaryFailureReason.API_KEY_INVALID);
            }
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.PROVIDER_ERROR);
        }

        Map<String, AiStructuredSummary.ParsedEntry> parsed = parseBatchStructuredResponse(response);
        if (parsed.isEmpty()) {
            log.warn("[AI] On-demand CVE summarize — empty structured parse for id={}", item.id());
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.PARSE_ERROR);
        }
        AiStructuredSummary.ParsedEntry entry = parsed.get(item.id());
        if (entry == null) {
            return CveSummarizeOutcome.fail(AiSummaryFailureReason.PARSE_ERROR);
        }
        return CveSummarizeOutcome.ok(entry);
    }

    private boolean isApiKeyUnavailable(AiSetting setting) {
        if (setting.getProvider() == AiProvider.LOCAL) {
            return false;
        }
        String key = decryptApiKey(setting);
        return key == null || key.isBlank();
    }

    @Transactional(readOnly = true)
    public Map<String, String> batchSummarizeLicenses(List<LicenseSummaryRequest> items) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        log.debug("[AI] batch.license start — {} item(s), provider={}", items.size(), setting.getProvider());
        String prompt = promptTemplates.batchLicensePrompt(items);
        return batchWithRetry(prompt, setting, items.size(), "license", "batch.license");
    }

    private Map<String, String> batchWithRetry(String prompt, AiSetting setting, int itemCount,
                                               String label, String operation) {
        try {
            Map<String, String> result = parseBatchDisplayResponse(delegate(prompt, setting, operation));
            if (!result.isEmpty()) {
                log.debug("[AI] {} parsed {} summary(ies)", operation, result.size());
                return result;
            }
            log.warn("[AI] Batch {} summary empty ({} items) — retrying once", label, itemCount);
            Map<String, String> retry = parseBatchDisplayResponse(delegate(prompt, setting, operation));
            if (!retry.isEmpty()) {
                log.debug("[AI] {} parsed {} summary(ies) on retry", operation, retry.size());
            }
            return retry;
        } catch (Exception e) {
            log.warn("[AI] Batch {} summary failed: {} — retrying once", label, e.getMessage());
            try {
                return parseBatchDisplayResponse(delegate(prompt, setting, operation));
            } catch (Exception retryEx) {
                log.warn("[AI] Batch {} summary retry failed: {}", label, retryEx.getMessage());
                return Map.of();
            }
        }
    }

    private Map<String, AiStructuredSummary.ParsedEntry> batchStructuredWithRetry(
            String prompt, AiSetting setting, int itemCount, String label, String operation) {
        try {
            Map<String, AiStructuredSummary.ParsedEntry> result =
                    parseBatchStructuredResponse(delegate(prompt, setting, operation));
            if (!result.isEmpty()) return result;
            log.warn("[AI] Batch {} structured empty ({} items) — retrying once", label, itemCount);
            return parseBatchStructuredResponse(delegate(prompt, setting, operation));
        } catch (Exception e) {
            log.warn("[AI] Batch {} structured failed: {}", label, e.getMessage());
            return Map.of();
        }
    }

    private String delegate(String prompt, AiSetting setting, String operation) {
        return delegate(prompt, setting, operation, null);
    }

    private String delegate(String prompt, AiSetting setting, String operation, String resolvedApiKeyOverride) {
        if (!usageLimiter.tryConsume(setting.getProvider())) {
            log.warn("[AI] Skipping {} — daily call cap reached for {}", operation, setting.getProvider());
            return null;
        }
        String resolvedApiKey = resolvedApiKeyOverride != null
                ? resolvedApiKeyOverride
                : decryptApiKey(setting);
        return switch (setting.getProvider()) {
            case OPENAI, LOCAL, GEMINI -> openAiClient.callWithSetting(prompt, setting, operation, resolvedApiKey);
            case ANTHROPIC             -> anthropicClient.callWithSetting(prompt, setting, operation, resolvedApiKey);
        };
    }

    private AiSetting getActiveSetting() {
        return aiSettingRepository.findByActiveTrue().orElseGet(() -> {
            log.debug("[AI] No active AI setting. Skipping analysis.");
            return null;
        });
    }

    private String decryptApiKey(AiSetting setting) {
        if (setting == null || setting.getApiKey() == null || setting.getApiKey().isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(setting.getApiKey());
        } catch (Exception e) {
            log.warn("[AI] Failed to decrypt API key for {} provider.", setting.getProvider());
            return null;
        }
    }

    private Map<String, String> parseBatchDisplayResponse(String response) {
        Map<String, AiStructuredSummary.ParsedEntry> structured = parseBatchStructuredResponse(response);
        Map<String, String> display = new LinkedHashMap<>();
        structured.forEach((id, entry) -> display.put(id, entry.formatForDisplay()));
        return display;
    }

    private Map<String, AiStructuredSummary.ParsedEntry> parseBatchStructuredResponse(String response) {
        if (response == null || response.isBlank()) return Map.of();
        try {
            int start = response.indexOf('[');
            int end   = response.lastIndexOf(']');
            if (start < 0 || end <= start) return Map.of();
            String json = response.substring(start, end + 1);
            List<Map<String, String>> list = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, AiStructuredSummary.ParsedEntry> result = new LinkedHashMap<>();
            for (Map<String, String> entry : list) {
                String id = entry.get("id");
                AiStructuredSummary.ParsedEntry parsed = AiStructuredSummary.ParsedEntry.fromMap(entry);
                if (id != null && parsed != null) {
                    result.put(id.strip(), parsed);
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
