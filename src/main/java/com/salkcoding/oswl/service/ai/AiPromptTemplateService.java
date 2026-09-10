package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.AiEffort;
import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.repository.ai.AiPreferencesRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads AI prompt templates from classpath resources and renders placeholders.
 *
 * <p>Default: {@code classpath:ai/prompts.properties}
 * Locale overlay: {@code classpath:ai/prompts_<locale>.properties} (e.g. {@code prompts_ko}, {@code prompts_ja})
 * Override path: {@code oswl.ai.prompts.location}
 */
@Slf4j
@Service
public class AiPromptTemplateService {

    private static final String DEFAULT_LOCATION = "classpath:ai/prompts.properties";
    private static final String OVERLAY_PATTERN = "classpath:ai/prompts_%s.properties";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResourceLoader resourceLoader;
    private final AiPreferencesRepository preferencesRepository;
    private final String promptsLocation;

    private volatile String locale = "en";

    private Properties templates = new Properties();

    /**
     * When true, {@code AiProvider.LOCAL} batch calls use the leaner {@code .local} prompt
     * variant (fewer fields, few-shot example) instead of the full schema meant for larger
     * cloud models — a 1.7B-class model following a 4-field JSON schema with a 6-line priority
     * rubric has a much higher chance of drifting from the schema than a cloud model does.
     */
    @Value("${oswl.ai.enrichment.local-simple-schema:true}")
    private boolean localSimpleSchema;

    /**
     * Cloud providers (OPENAI/ANTHROPIC/GEMINI) get a pipe-delimited batch item format
     * instead of repeating a full field label on every line — the labels alone cost ~17 tokens
     * per item. LOCAL never uses this: smaller models already pull the opposite direction
     * (they need explicit labels to avoid mis-assigning fields), and local tokens are effectively
     * free (self-hosted), so the accuracy risk is not worth the saving there.
     */
    @Value("${oswl.ai.enrichment.compact-batch-prompts:true}")
    private boolean compactBatchPromptsEnabled;

    public AiPromptTemplateService(
            ResourceLoader resourceLoader,
            AiPreferencesRepository preferencesRepository,
            @Value("${oswl.ai.prompts.location:" + DEFAULT_LOCATION + "}") String promptsLocation) {
        this.resourceLoader = resourceLoader;
        this.preferencesRepository = preferencesRepository;
        this.promptsLocation = promptsLocation;
    }

    @PostConstruct
    void load() {
        loadWithLocale(locale);
    }

    /** Reload templates after preferences change (locale overlay). */
    public void reloadWithLocale(String newLocale) {
        this.locale = newLocale != null && !newLocale.isBlank() ? newLocale.strip() : "en";
        loadWithLocale(this.locale);
    }

    private void loadWithLocale(String activeLocale) {
        templates = loadFrom(resourceLoader.getResource(promptsLocation));
        // Any locale with a prompts_<locale>.properties overlay is supported (ko, ja, ...).
        if (activeLocale != null && !activeLocale.isBlank() && !"en".equalsIgnoreCase(activeLocale)) {
            String code = activeLocale.strip().toLowerCase(java.util.Locale.ROOT);
            Resource overlay = resourceLoader.getResource(String.format(OVERLAY_PATTERN, code));
            if (overlay.exists()) {
                overlay(loadFrom(overlay));
                log.info("[AI] Applied '{}' prompt overlay from {}", code, overlay);
            } else {
                log.debug("[AI] No prompt overlay for locale '{}' — using default templates", code);
            }
        }
        applyDbOverrides(readPreferences().getPromptOverrides());
    }

    /**
     * Language directive appended to the system prompt so the model answers in the language of the
     * person who triggered the scan ({@link AiLanguageContext}), independent of the globally
     * configured template overlay. Returns an empty string when no request language is bound.
     */
    private String languageDirective() {
        String lang = AiLanguageContext.currentLanguageName();
        return lang == null ? "" : "\nAlways write every free-text answer in " + lang + ".";
    }

    private AiPreferences readPreferences() {
        return preferencesRepository.findById(AiPreferences.SINGLETON_ID)
                .orElseGet(() -> AiPreferences.defaults("en", 10, 8, "CRITICAL,HIGH", 0));
    }

    public String getSystemPrompt() {
        return require("system.default") + languageDirective();
    }

    public String getSystemPrompt(AiProvider provider) {
        if (provider == null) return getSystemPrompt();
        String key = "system." + provider.name().toLowerCase();
        if (templates.containsKey(key)) {
            return templates.getProperty(key) + languageDirective();
        }
        return getSystemPrompt();
    }

    public double getTemperature() {
        Double pref = readPreferences().getTemperature();
        if (pref != null) return pref;
        return parseDouble(require("params.temperature"), 0.15);
    }

    public int getMaxTokens() {
        Integer pref = readPreferences().getMaxTokens();
        if (pref != null) return pref;
        return (int) parseDouble(require("params.maxTokens"), 1200);
    }

    /**
     * Reasoning effort selected in AI settings. {@link AiEffort#DEFAULT} means the clients send no
     * effort parameter at all, which is what keeps older models and local runtimes working.
     */
    public AiEffort getReasoningEffort() {
        return readPreferences().getReasoningEffort();
    }

    /**
     * Operation budget with room for the model to think first.
     *
     * <p>{@code max_tokens} is a ceiling on the <em>whole</em> response, and on every current cloud
     * model that includes the reasoning the model does before it writes anything. The per-operation
     * budgets in {@code prompts.properties} (256 for a posture insight, 900 for combined insights)
     * were sized against the embedded llama.cpp model, which runs with reasoning switched off —
     * on OpenAI/Anthropic/Gemini the same 256 is spent reasoning and the answer comes back empty
     * or truncated. That is why insights appeared for the embedded model but not for a configured
     * cloud provider.
     *
     * <p>So a headroom allowance is added on top of the template budget whenever the model is
     * likely to reason: any cloud provider, or any provider once the user has explicitly raised
     * the effort level. Embedded/local at the default effort keeps the original tight budgets —
     * a small local model given a large ceiling tends to ramble rather than stop.
     *
     * <p>The ceiling is not a target: a model that does not reason still stops at its own end of
     * turn well before it, so the extra headroom costs nothing when it is not needed.
     */
    public int getMaxTokens(String operation, AiProvider provider) {
        return getMaxTokens(operation) + reasoningHeadroom(provider, getReasoningEffort());
    }

    private static int reasoningHeadroom(AiProvider provider, AiEffort effort) {
        boolean cloudReasoning = provider == AiProvider.OPENAI
                || provider == AiProvider.ANTHROPIC
                || provider == AiProvider.GEMINI;
        if (!cloudReasoning && effort.isDefault()) {
            return 0;
        }
        return switch (effort) {
            case DEFAULT, LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> 4096;
            case XHIGH -> 6144;
            case MAX -> 8192;
        };
    }

    /**
     * Operation-specific max_tokens (e.g. {@code "batch.cve"}, {@code "security.posture"}).
     * A short free-text insight and a 10-item batch JSON array need very different budgets —
     * one generic value either truncates the batch or wastes headroom on the short prompts.
     *
     * <p>Priority: {@code params.maxTokens.<operation>} (if present) &gt; {@link #getMaxTokens()}
     * (DB override, then {@code params.maxTokens}). An operation-specific key intentionally
     * outranks the DB override — a batch operation's token floor must not be silently cut
     * below what a truncation-free response requires just because the user set a smaller
     * global default for the short free-text insights.
     */
    public int getMaxTokens(String operation) {
        if (operation != null) {
            String key = "params.maxTokens." + operation;
            String value = templates.getProperty(key);
            if (value != null) {
                return (int) parseDouble(value, getMaxTokens());
            }
        }
        return getMaxTokens();
    }

    private void applyDbOverrides(String jsonOverrides) {
        if (jsonOverrides == null || jsonOverrides.isBlank()) return;
        try {
            Map<String, String> overrides = MAPPER.readValue(jsonOverrides, new TypeReference<>() {});
            overrides.forEach(templates::setProperty);
            log.info("[AI] Applied {} DB prompt override(s)", overrides.size());
        } catch (Exception e) {
            log.warn("[AI] Invalid prompt_overrides JSON: {}", e.getMessage());
        }
    }

    public static final List<String> EDITABLE_PROMPT_KEYS = List.of(
            "system.default",
            "batch.cve.header",
            "batch.cve.item",
            "batch.license.header",
            "batch.license.item",
            "cve.single",
            "license.single"
    );

    public String cveSingle(String cveId, String severity, double cvssScore, String component) {
        return cveSingleRich(cveId, severity, cvssScore, component,
                "-", "-", "-", "-", "-", "unknown", "UNKNOWN");
    }

    private static Map<String, Object> vars(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("vars requires key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private String cveSingleRich(String cveId, String severity, double cvssScore, String component,
                                String title, String osvSummary, String fixVersion, String cweId,
                                String cvssVector, String dependencyType, String patchability) {
        return render("cve.single", vars(
                "cveId", cveId,
                "severity", severity,
                "cvssScore", formatCvss(cvssScore),
                "component", component,
                "title", AiEnrichmentContextBuilder.orDash(title),
                "osvSummary", AiEnrichmentContextBuilder.orDash(osvSummary),
                "fixVersion", AiEnrichmentContextBuilder.orDash(fixVersion),
                "cweId", AiEnrichmentContextBuilder.orDash(cweId),
                "cvssVector", AiEnrichmentContextBuilder.orDash(cvssVector),
                "dependencyType", dependencyType,
                "patchability", patchability));
    }

    public String cveSingleWithType(String cveId, String severity, double cvssScore,
                                    String cveType, String component) {
        return render("cve.single.withType", vars(
                "cveId", cveId,
                "severity", severity,
                "cvssScore", formatCvss(cvssScore),
                "cveType", cveType,
                "component", component,
                "title", "-",
                "osvSummary", "-",
                "fixVersion", "-",
                "cweId", "-",
                "dependencyType", "unknown"));
    }

    public String securityTrend(String projectName, int secDelta, String recentVersions, String changeDetails) {
        return render("security.trend", Map.of(
                "projectName", projectName,
                "securityDirection", direction(secDelta),
                "securityDelta", abs(secDelta),
                "recentVersions", recentVersions,
                "changeDetails", nullToDash(changeDetails)));
    }

    public String licenseTrend(String projectName, int licDelta, String recentVersions, String changeDetails) {
        return render("license.trend", Map.of(
                "projectName", projectName,
                "licenseDirection", direction(licDelta),
                "licenseDelta", abs(licDelta),
                "recentVersions", recentVersions,
                "changeDetails", nullToDash(changeDetails)));
    }

    public String licenseSingle(String licenseName, String licenseStatus, String component,
                                String ecosystem, String dependencyType, String latestVersion) {
        return render("license.single", Map.of(
                "licenseName", licenseName,
                "licenseStatus", licenseStatus,
                "component", component,
                "ecosystem", AiEnrichmentContextBuilder.orDash(ecosystem),
                "dependencyType", dependencyType,
                "latestVersion", AiEnrichmentContextBuilder.orDash(latestVersion)));
    }

    public String securityPosture(AiEnrichmentContextBuilder.PostureContext ctx, String projectName) {
        return render("security.posture", vars(
                "projectName", projectName,
                "critical", ctx.critical(),
                "high", ctx.high(),
                "medium", ctx.medium(),
                "low", ctx.low(),
                "totalComponents", ctx.totalComponents(),
                "patchableCount", ctx.patchableCount(),
                "nonPatchableCount", ctx.nonPatchableCount(),
                "directCriticalHigh", ctx.directCriticalHigh(),
                "topIssues", ctx.topIssues()));
    }

    public String versionDiff(String projectName, String fromVersion, String toVersion,
                              int added, int removed, int updated, int newThreats, String threatDetails) {
        return render("version.diff", Map.of(
                "projectName", projectName,
                "fromVersion", fromVersion,
                "toVersion", toVersion,
                "added", added,
                "removed", removed,
                "updated", updated,
                "newThreats", newThreats,
                "threatDetails", nullToDash(threatDetails)));
    }

    /**
     * Posture + security-trend + license-trend + version-diff folded into one JSON call
     * instead of 4 separate free-form ones. {@code hasHistory=false} (first scan for the
     * project, no prior completed scan) renders the reduced posture-only schema — the trend/
     * diff sections would otherwise ask the model to invent a "no change" narrative from
     * nothing.
     */
    public String combinedInsightsPrompt(String projectName, AiEnrichmentContextBuilder.PostureContext posture,
                                         boolean hasHistory, int secDelta, int licDelta, String recentVersions,
                                         String secChangeDetails, String licChangeDetails,
                                         String fromVersion, String toVersion,
                                         int added, int removed, int updated, int newThreats, String threatDetails) {
        if (!hasHistory) {
            return render("insights.combined.postureOnly", vars(
                    "projectName", projectName,
                    "critical", posture.critical(),
                    "high", posture.high(),
                    "medium", posture.medium(),
                    "low", posture.low(),
                    "totalComponents", posture.totalComponents(),
                    "patchableCount", posture.patchableCount(),
                    "nonPatchableCount", posture.nonPatchableCount(),
                    "directCriticalHigh", posture.directCriticalHigh(),
                    "topIssues", posture.topIssues()));
        }
        return render("insights.combined.full", vars(
                "projectName", projectName,
                "critical", posture.critical(),
                "high", posture.high(),
                "medium", posture.medium(),
                "low", posture.low(),
                "totalComponents", posture.totalComponents(),
                "patchableCount", posture.patchableCount(),
                "nonPatchableCount", posture.nonPatchableCount(),
                "directCriticalHigh", posture.directCriticalHigh(),
                "topIssues", posture.topIssues(),
                "recentVersions", recentVersions,
                "securityDirection", direction(secDelta),
                "securityDelta", abs(secDelta),
                "secChangeDetails", nullToDash(secChangeDetails),
                "licenseDirection", direction(licDelta),
                "licenseDelta", abs(licDelta),
                "licChangeDetails", nullToDash(licChangeDetails),
                "fromVersion", fromVersion,
                "toVersion", toVersion,
                "added", added,
                "removed", removed,
                "updated", updated,
                "newThreats", newThreats,
                "threatDetails", nullToDash(threatDetails)));
    }

    public String testConnection() {
        return require("test.connection");
    }

    public String batchCvePrompt(List<AiAnalysisService.CveSummaryRequest> items, String deploymentProfile) {
        return batchCvePrompt(items, deploymentProfile, null);
    }

    public String batchCvePrompt(List<AiAnalysisService.CveSummaryRequest> items, String deploymentProfile,
                                 AiProvider provider) {
        boolean compact = useCompactBatchFormat(provider);
        String header = render(compact ? "batch.cve.header.compact" : "batch.cve.header", provider, Map.of(
                "deploymentProfile", deploymentProfile != null ? deploymentProfile : "COMMERCIAL_PRODUCT"));
        StringBuilder sb = new StringBuilder(header);
        String itemKey = compact ? "batch.cve.item.compact" : "batch.cve.item";
        // compact mode drops the trailing "-" for a missing field to blank ("||") — every
        // char counts once the label is gone, and an empty field is still unambiguous in a
        // fixed pipe-delimited position. The labeled format keeps "-" (unambiguous either way).
        java.util.function.Function<String, String> missing = compact ? s -> s != null ? s : "" : AiEnrichmentContextBuilder::orDash;
        for (int i = 0; i < items.size(); i++) {
            AiAnalysisService.CveSummaryRequest r = items.get(i);
            sb.append(render(itemKey, vars(
                    "index", i + 1,
                    "id", r.id(),
                    "component", r.component(),
                    "severity", r.severity(),
                    "cvssScore", formatCvss(r.cvssScore()),
                    "epss", r.epssScore() != null ? String.format("%.3f", r.epssScore()) : (compact ? "" : "-"),
                    "kevListed", r.kevListed() ? "yes" : "no",
                    "title", missing.apply(r.title()),
                    "osvSummary", missing.apply(r.osvSummary()),
                    "fixVersion", missing.apply(r.fixVersion()),
                    "cweId", missing.apply(r.cweId()),
                    "cvssVector", missing.apply(r.cvssVector()),
                    "dependencyType", r.dependencyType(),
                    "patchability", r.patchability())));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Pipe-delimited compact batch prompts are cloud-only (see {@link #compactBatchPromptsEnabled}). */
    private boolean useCompactBatchFormat(AiProvider provider) {
        return compactBatchPromptsEnabled && provider != null && provider != AiProvider.LOCAL;
    }

    public String batchLicensePrompt(List<AiAnalysisService.LicenseSummaryRequest> items,
                                     String deploymentProfile) {
        return batchLicensePrompt(items, deploymentProfile, null);
    }

    public String batchLicensePrompt(List<AiAnalysisService.LicenseSummaryRequest> items,
                                     String deploymentProfile, AiProvider provider) {
        boolean compact = useCompactBatchFormat(provider);
        StringBuilder sb = new StringBuilder(render(compact ? "batch.license.header.compact" : "batch.license.header",
                provider, Map.of("deploymentProfile", deploymentProfile != null ? deploymentProfile : "COMMERCIAL_PRODUCT")));
        String itemKey = compact ? "batch.license.item.compact" : "batch.license.item";
        java.util.function.Function<String, String> missing = compact ? s -> s != null ? s : "" : AiEnrichmentContextBuilder::orDash;
        for (int i = 0; i < items.size(); i++) {
            AiAnalysisService.LicenseSummaryRequest r = items.get(i);
            sb.append(render(itemKey, Map.of(
                    "index", i + 1,
                    "id", r.id(),
                    "licenseName", r.licenseName(),
                    "licenseStatus", r.licenseStatus(),
                    "policyReason", missing.apply(r.policyReason()),
                    "component", r.component(),
                    "ecosystem", missing.apply(r.ecosystem()),
                    "dependencyType", r.dependencyType(),
                    "latestVersion", missing.apply(r.latestVersion()))));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public String render(String key, Map<String, ?> vars) {
        return render(key, null, vars);
    }

    /**
     * Same as {@link #render(String, Map)}, but resolves {@code key + ".local"} first when
     * {@code provider} is {@link AiProvider#LOCAL} and {@link #localSimpleSchema} is enabled —
     * falling back to {@code key} when no {@code .local} variant is defined for it.
     */
    public String render(String key, AiProvider provider, Map<String, ?> vars) {
        String result = requireForProvider(key, provider);
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        if (result.contains("{")) {
            log.warn("[AI] Unresolved placeholders remain in prompt '{}'", key);
        }
        return result;
    }

    private String requireForProvider(String key, AiProvider provider) {
        if (provider == AiProvider.LOCAL && localSimpleSchema) {
            String localValue = templates.getProperty(key + ".local");
            if (localValue != null) {
                return localValue;
            }
        }
        return require(key);
    }

    public Map<String, String> snapshot() {
        Map<String, String> copy = new LinkedHashMap<>();
        for (String name : templates.stringPropertyNames()) {
            copy.put(name, templates.getProperty(name));
        }
        return Map.copyOf(copy);
    }

    private String require(String key) {
        String value = templates.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Missing AI prompt template: " + key);
        }
        return value;
    }

    private void overlay(Properties overlay) {
        for (String name : overlay.stringPropertyNames()) {
            templates.setProperty(name, overlay.getProperty(name));
        }
    }

    private static Properties loadFrom(Resource resource) {
        Properties props = new Properties();
        if (!resource.exists()) {
            throw new IllegalStateException("AI prompts resource not found: " + resource);
        }
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
            log.info("[AI] Loaded {} prompt template(s) from {}", props.size(), resource);
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load AI prompts from " + resource, e);
        }
    }

    private String direction(int delta) {
        if ("ko".equalsIgnoreCase(locale)) {
            return delta >= 0 ? "증가" : "감소";
        }
        return delta >= 0 ? "increased" : "decreased";
    }

    private static int abs(int delta) {
        return Math.abs(delta);
    }

    private static String formatCvss(double score) {
        return String.format("%.1f", score);
    }

    private static String nullToDash(String value) {
        return value != null && !value.isBlank() ? value.strip() : "-";
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.strip());
        } catch (Exception e) {
            return fallback;
        }
    }
}
