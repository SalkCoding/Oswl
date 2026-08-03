package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.exception.AiSummaryFailureReason;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.dto.AiConnectionTestResult;
import com.salkcoding.oswl.service.EnrichmentProgressContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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
    private final AiConnectionDiagnostics connectionDiagnostics;

    /**
     * Batch items are split into chunks of this size before the prompt is assembled, so a
     * large CVE/license limit cannot produce a single prompt whose output alone exceeds
     * max_tokens (which previously truncated the JSON array mid-object and silently dropped
     * the whole batch — see {@link #splitToFitBudget}).
     */
    @Value("${oswl.ai.enrichment.batch-chunk-size:5}")
    private int batchChunkSize;

    /**
     * Retry budget for a batch chunk that comes back empty or fails to parse. Each retry
     * splits the failing chunk in half and retries the halves independently (instead of
     * resending the identical prompt — a truncated/parse-failed response is usually a budget
     * problem, which an identical retry cannot fix) until this many split levels are spent or
     * the chunk cannot be split further. Transport-level retries (429 backoff) are handled
     * separately by OpenAiClient/AnthropicClient and are not affected by this setting.
     */
    @Value("${oswl.ai.enrichment.max-retries:1}")
    private int maxRetries;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Conservative chars-per-token ratio (CJK-worst-case) used only to size batches, never to bill usage. */
    private static final double CHARS_PER_TOKEN = 2.5;
    /**
     * Worst-case output size for one batch entry: the JSON structure/id/priority overhead
     * plus the field budgets enforced in {@code batch.cve.header}/{@code batch.license.header}
     * ("summary" ≤120 chars, "recommendedAction" ≤100 chars — both batches share the same
     * {id, summary, recommendedAction, priority} response shape).
     */
    private static final int BATCH_ITEM_OUTPUT_CHAR_BUDGET = 70 + 120 + 100;

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
        return delegatePlainText(prompt, setting, "cve.single");
    }

    /** Plain-text delegate — unwraps JSON/fence noise smaller models add to prose answers. */
    private String delegatePlainText(String prompt, AiSetting setting, String operation) {
        // D2: free-form (prose) calls stream token-by-token when the caller opened an
        // enrichment preview scope; batch/JSON calls never stream (raw partial JSON is
        // meaningless as a user-facing preview). Outside a scope the sink is null and the
        // non-streaming path is used exactly as before.
        return AiResponseSanitizer.sanitizePlainText(
                delegate(prompt, setting, operation, null, EnrichmentProgressContext.currentPreviewSink()));
    }

    @Transactional(readOnly = true)
    public String generateSecurityTrendInsight(String projectName, int secDelta,
                                               String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityTrend(projectName, secDelta, recentVersions, changeDetails);
        return delegatePlainText(prompt, setting, "security.trend");
    }

    @Transactional(readOnly = true)
    public String generateLicenseTrendInsight(String projectName, int licDelta,
                                              String recentVersions, String changeDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.licenseTrend(projectName, licDelta, recentVersions, changeDetails);
        return delegatePlainText(prompt, setting, "license.trend");
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
        return delegatePlainText(prompt, setting, "license.single");
    }

    @Transactional(readOnly = true)
    public String summarizeSecurityPosture(String projectName, AiEnrichmentContextBuilder.PostureContext ctx) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.securityPosture(ctx, projectName);
        return delegatePlainText(prompt, setting, "security.posture");
    }

    @Transactional(readOnly = true)
    public String summarizeVersionDiff(String projectName, String fromVersion, String toVersion,
                                       int added, int removed, int updated, int newThreats,
                                       String threatDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.versionDiff(projectName, fromVersion, toVersion,
                added, removed, updated, newThreats, threatDetails);
        return delegatePlainText(prompt, setting, "version.diff");
    }

    /** F2: posture + security-trend + license-trend + version-diff folded into one AI call. */
    public record CombinedInsights(String posture, String securityTrend, String licenseTrend, String versionDiff) {}

    /**
     * F2: one JSON call producing all 4 free-form scan-level insights instead of 4 separate
     * prose calls. {@code hasHistory=false} (project's first scan) omits the trend/diff
     * arguments and uses the reduced posture-only schema/response. A partial parse (some
     * fields present, others missing) still returns those fields — the caller (block 3 of
     * {@code VulnerabilityEnrichmentService.enrichWithAiBody}) persists whichever insights
     * came back and leaves the rest untouched, same "partial success is still success"
     * philosophy as the CVE/license batch chunking.
     */
    @Transactional(readOnly = true)
    public CombinedInsights generateCombinedInsights(String projectName,
            AiEnrichmentContextBuilder.PostureContext posture, boolean hasHistory,
            int secDelta, int licDelta, String recentVersions, String secChangeDetails, String licChangeDetails,
            String fromVersion, String toVersion, int added, int removed, int updated, int newThreats,
            String threatDetails) {
        AiSetting setting = getActiveSetting();
        if (setting == null) return null;
        String prompt = promptTemplates.combinedInsightsPrompt(projectName, posture, hasHistory,
                secDelta, licDelta, recentVersions, secChangeDetails, licChangeDetails,
                fromVersion, toVersion, added, removed, updated, newThreats, threatDetails);
        // JSON response — never streamed to the D2 live preview (raw partial JSON is
        // meaningless as a user-facing preview, same reasoning as the CVE/license batches).
        String response = delegate(prompt, setting, "insights.combined");
        Map<String, String> parsed = parseCombinedInsightsResponse(response);
        if (parsed.isEmpty()) return null;
        return new CombinedInsights(
                AiResponseSanitizer.sanitizePlainText(parsed.get("posture")),
                AiResponseSanitizer.sanitizePlainText(parsed.get("securityTrend")),
                AiResponseSanitizer.sanitizePlainText(parsed.get("licenseTrend")),
                AiResponseSanitizer.sanitizePlainText(parsed.get("versionDiff")));
    }

    private Map<String, String> parseCombinedInsightsResponse(String response) {
        if (response == null || response.isBlank()) return Map.of();
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();
            String json = response.substring(start, end + 1);
            Map<String, String> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            parsed.values().removeIf(v -> v == null || v.isBlank());
            return parsed;
        } catch (Exception e) {
            log.warn("[AI] insights.combined parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Transactional(readOnly = true)
    public boolean isAiConfigured() {
        return aiSettingRepository.findByActiveTrue().isPresent();
    }

    public boolean testConnection(AiSetting setting) {
        return testConnectionDetailed(setting).success();
    }

    /**
     * Connection probe with localized, actionable failure messages for the settings UI.
     *
     * <p>This lists the provider's models rather than sending a throwaway completion. Listing
     * is free on every supported provider and exercises the same failure surface a real call
     * would — DNS, TLS, reachability, credentials — so testing a provider no longer burns
     * tokens or a slot in the daily call cap. As a bonus the returned catalogue lets us warn
     * when the configured model id is not one the account can actually use, which a
     * "reply OK" probe could only discover by paying for a failed request.
     */
    public AiConnectionTestResult testConnectionDetailed(AiSetting setting) {
        Optional<AiConnectionTestResult> preflight = connectionDiagnostics.preflight(setting);
        if (preflight.isPresent()) {
            return preflight.get();
        }

        try {
            String apiKey = setting.getApiKey();
            List<String> availableModels = switch (setting.getProvider()) {
                case ANTHROPIC -> anthropicClient.probeModels(apiKey);
                case OPENAI, GEMINI, LOCAL -> openAiClient.probeModels(setting, apiKey);
            };
            log.info("[AI] {} provider connection test succeeded ({} model(s) listed)",
                    setting.getProvider(), availableModels.size());
            return connectionDiagnostics.successFor(setting, availableModels);
        } catch (Exception e) {
            log.warn("[AI] {} provider connection test failed: {}", setting.getProvider(), e.getMessage());
            return connectionDiagnostics.fromException(setting, e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, AiStructuredSummary.ParsedEntry> batchSummarizeCves(
            List<CveSummaryRequest> items, String deploymentProfile) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        log.debug("[AI] batch.cve start — {} item(s), provider={}", items.size(), setting.getProvider());
        Map<String, AiStructuredSummary.ParsedEntry> merged = new LinkedHashMap<>();
        int processed = 0;
        for (List<CveSummaryRequest> chunk : chunkForOutputBudget(items, "batch.cve")) {
            merged.putAll(batchStructuredWithRetry(chunk, deploymentProfile, setting, "CVE", "batch.cve", maxRetries));
            processed += chunk.size();
            reportBatchProgress(processed, items.size());
        }
        return merged;
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
    public Map<String, String> batchSummarizeLicenses(List<LicenseSummaryRequest> items, String deploymentProfile) {
        AiSetting setting = getActiveSetting();
        if (setting == null || items.isEmpty()) return Map.of();
        log.debug("[AI] batch.license start — {} item(s), provider={}", items.size(), setting.getProvider());
        Map<String, String> merged = new LinkedHashMap<>();
        int processed = 0;
        for (List<LicenseSummaryRequest> chunk : chunkForOutputBudget(items, "batch.license")) {
            merged.putAll(batchWithRetry(chunk, deploymentProfile, setting, "license", "batch.license", maxRetries));
            processed += chunk.size();
            reportBatchProgress(processed, items.size());
        }
        return merged;
    }

    /** D2: reports "N/M done" for batch chunks — only when the caller opened a progress scope. */
    private void reportBatchProgress(int processed, int total) {
        IntConsumer progress = EnrichmentProgressContext.currentBatchProgress();
        if (progress != null) {
            progress.accept(Math.min(processed, total));
        }
    }

    /**
     * Splits a batch into chunks of {@link #batchChunkSize}, then further halves any chunk
     * whose worst-case JSON output would exceed 80% of {@code max_tokens} — the response gets
     * cut off mid-object well before that, so this keeps the array reliably parseable instead
     * of silently losing the whole batch to a truncated JSON tail.
     *
     * @param operation the max_tokens key this batch will actually be called with
     *                  (see {@link AiPromptTemplateService#getMaxTokens(String)}) — the budget
     *                  estimate must match the real ceiling, not the generic default.
     */
    private <T> List<List<T>> chunkForOutputBudget(List<T> items, String operation) {
        int step = effectiveBatchChunkSize();
        List<List<T>> initial = new ArrayList<>();
        for (int i = 0; i < items.size(); i += step) {
            initial.add(items.subList(i, Math.min(i + step, items.size())));
        }
        int maxTokens = promptTemplates.getMaxTokens(operation);
        List<List<T>> result = new ArrayList<>();
        for (List<T> chunk : initial) {
            splitToFitBudget(chunk, maxTokens, result);
        }
        return result;
    }

    /**
     * Guards against an infinite loop in {@link #chunkForOutputBudget} if {@code batchChunkSize}
     * is ever non-positive (misconfiguration, or a plain-Mockito unit test that never lets Spring
     * resolve the {@code @Value} default) by degrading to the smallest safe chunk size instead of
     * silently batching everything unbounded.
     */
    private int effectiveBatchChunkSize() {
        return Math.max(1, batchChunkSize);
    }

    private <T> void splitToFitBudget(List<T> chunk, int maxTokens, List<List<T>> out) {
        if (chunk.size() <= 1) {
            out.add(chunk);
            return;
        }
        double estimatedTokens = (chunk.size() * (double) BATCH_ITEM_OUTPUT_CHAR_BUDGET) / CHARS_PER_TOKEN;
        if (estimatedTokens <= maxTokens * 0.8) {
            out.add(chunk);
            return;
        }
        log.debug("[AI] batch chunk (size={}) estimated output ~{} tokens exceeds 80% of max_tokens={} — splitting",
                chunk.size(), Math.round(estimatedTokens), maxTokens);
        int mid = chunk.size() / 2;
        splitToFitBudget(chunk.subList(0, mid), maxTokens, out);
        splitToFitBudget(chunk.subList(mid, chunk.size()), maxTokens, out);
    }

    /**
     * Runs one batch call and, on an empty or failed parse, retries by splitting the chunk in
     * half rather than resending the identical prompt — a truncated/unparseable response is
     * usually an output-budget problem (see C1), which an identical retry cannot fix.
     * {@code retriesLeft} bounds the split recursion depth, not the raw request count: each
     * split level can issue up to 2 requests (one per half), so a chunk that fails completely
     * can cost more than {@code 1 + retriesLeft} requests in the worst case — traded
     * deliberately for a much better chance of salvaging partial results.
     * Transport-level retries (429 backoff) happen one layer down in OpenAiClient/AnthropicClient
     * and are untouched by this budget.
     */
    private Map<String, String> batchWithRetry(List<LicenseSummaryRequest> chunk, String deploymentProfile,
                                               AiSetting setting, String label, String operation, int retriesLeft) {
        String prompt = promptTemplates.batchLicensePrompt(chunk, deploymentProfile, setting.getProvider());
        Map<String, String> result;
        try {
            result = parseBatchDisplayResponse(delegate(prompt, setting, operation));
        } catch (Exception e) {
            log.warn("[AI] Batch {} summary failed: {}", label, e.getMessage());
            result = Map.of();
        }
        if (!result.isEmpty()) {
            log.debug("[AI] {} parsed {} summary(ies)", operation, result.size());
            return result;
        }
        if (retriesLeft <= 0 || chunk.size() <= 1) {
            return result;
        }
        log.warn("[AI] Batch {} summary empty ({} items) — splitting into halves and retrying", label, chunk.size());
        int mid = chunk.size() / 2;
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(batchWithRetry(chunk.subList(0, mid), deploymentProfile, setting, label, operation, retriesLeft - 1));
        merged.putAll(batchWithRetry(chunk.subList(mid, chunk.size()), deploymentProfile, setting, label, operation, retriesLeft - 1));
        return merged;
    }

    /** Same split-retry strategy as {@link #batchWithRetry} — see its Javadoc. */
    private Map<String, AiStructuredSummary.ParsedEntry> batchStructuredWithRetry(
            List<CveSummaryRequest> chunk, String deploymentProfile, AiSetting setting,
            String label, String operation, int retriesLeft) {
        String prompt = promptTemplates.batchCvePrompt(chunk, deploymentProfile, setting.getProvider());
        Map<String, AiStructuredSummary.ParsedEntry> result;
        try {
            result = parseBatchStructuredResponse(delegate(prompt, setting, operation));
        } catch (Exception e) {
            log.warn("[AI] Batch {} structured failed: {}", label, e.getMessage());
            result = Map.of();
        }
        if (!result.isEmpty()) {
            return result;
        }
        if (retriesLeft <= 0 || chunk.size() <= 1) {
            return result;
        }
        log.warn("[AI] Batch {} structured empty ({} items) — splitting into halves and retrying", label, chunk.size());
        int mid = chunk.size() / 2;
        Map<String, AiStructuredSummary.ParsedEntry> merged = new LinkedHashMap<>();
        merged.putAll(batchStructuredWithRetry(chunk.subList(0, mid), deploymentProfile, setting, label, operation, retriesLeft - 1));
        merged.putAll(batchStructuredWithRetry(chunk.subList(mid, chunk.size()), deploymentProfile, setting, label, operation, retriesLeft - 1));
        return merged;
    }

    private String delegate(String prompt, AiSetting setting, String operation) {
        return delegate(prompt, setting, operation, null);
    }

    private String delegate(String prompt, AiSetting setting, String operation, String resolvedApiKeyOverride) {
        return delegate(prompt, setting, operation, resolvedApiKeyOverride, null);
    }

    private String delegate(String prompt, AiSetting setting, String operation, String resolvedApiKeyOverride,
                            Consumer<String> previewSink) {
        if (!usageLimiter.tryConsume(setting.getProvider())) {
            log.warn("[AI] Skipping {} — daily call cap reached for {}", operation, setting.getProvider());
            return null;
        }
        String resolvedApiKey = resolvedApiKeyOverride != null
                ? resolvedApiKeyOverride
                : decryptApiKey(setting);
        return switch (setting.getProvider()) {
            // The streaming (5-arg) overload is only used when a preview sink is bound, so
            // callers without a scope take the exact pre-D2 path. Anthropic has no streaming
            // path (D2 scope is the OpenAI-compatible client) — previews are simply absent.
            case OPENAI, LOCAL, GEMINI -> previewSink != null
                    ? openAiClient.callWithSetting(prompt, setting, operation, resolvedApiKey, previewSink)
                    : openAiClient.callWithSetting(prompt, setting, operation, resolvedApiKey);
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
