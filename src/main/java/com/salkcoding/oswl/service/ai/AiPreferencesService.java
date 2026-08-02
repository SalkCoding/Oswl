package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.domain.enums.AiEffort;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.AiPreferencesRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPreferencesService {

    private final AiPreferencesRepository repository;
    @Lazy
    private final AiPromptTemplateService promptTemplateService;

    @Value("${oswl.ai.prompts.locale:en}")
    private String defaultLocale;

    @Value("${oswl.ai.enrichment.cve-limit:10}")
    private int defaultCveLimit;

    @Value("${oswl.ai.enrichment.license-limit:8}")
    private int defaultLicenseLimit;

    @Value("${oswl.ai.enrichment.cve-severities:CRITICAL,HIGH}")
    private String defaultCveSeverities;

    @Value("${oswl.ai.enrichment.daily-call-cap:0}")
    private int defaultDailyCallCap;

    @PostConstruct
    void init() {
        ensureDefaults();
        promptTemplateService.reloadWithLocale(getEffective().getPromptsLocale());
    }

    @Transactional(readOnly = true)
    public AiPreferences getEffective() {
        return repository.findById(AiPreferences.SINGLETON_ID)
                .orElseGet(this::defaultPreferences);
    }

    public String getPromptsLocale() {
        return getEffective().getPromptsLocale();
    }

    public int getCveLimit() {
        return getEffective().getCveLimit();
    }

    public int getLicenseLimit() {
        return getEffective().getLicenseLimit();
    }

    public Set<RiskLevel> getCveSeveritySet() {
        String raw = getEffective().getCveSeverities();
        if (raw == null || raw.isBlank()) {
            return EnumSet.of(RiskLevel.CRITICAL, RiskLevel.HIGH);
        }
        Set<RiskLevel> levels = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(RiskLevel::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RiskLevel.class)));
        return levels.isEmpty() ? EnumSet.of(RiskLevel.CRITICAL, RiskLevel.HIGH) : levels;
    }

    /** Effort selected in settings; {@link AiEffort#DEFAULT} means "send no effort parameter". */
    public AiEffort getReasoningEffort() {
        return getEffective().getReasoningEffort();
    }

    /** Whether provider/locale changes may regenerate insights for already-completed scans. */
    public boolean isAutoBackfillInsights() {
        return getEffective().isAutoBackfillInsights();
    }

    /**
     * Backward-compatible overload — keeps the caller's current effort and auto-backfill choice.
     */
    @Transactional
    public AiPreferences save(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities,
                            Double temperature, Integer maxTokens, int dailyCallCap,
                            String promptOverrides, DeploymentProfile defaultDeploymentProfile) {
        AiPreferences current = getEffective();
        return save(promptsLocale, cveLimit, licenseLimit, cveSeverities, temperature, maxTokens,
                dailyCallCap, promptOverrides, defaultDeploymentProfile,
                current.getReasoningEffort(), current.isAutoBackfillInsights());
    }

    @Transactional
    public AiPreferences save(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities,
                            Double temperature, Integer maxTokens, int dailyCallCap,
                            String promptOverrides, DeploymentProfile defaultDeploymentProfile,
                            AiEffort reasoningEffort, boolean autoBackfillInsights) {
        String locale = normalizeLocale(promptsLocale);
        int cve = clamp(cveLimit, 1, 50, defaultCveLimit);
        int lic = clamp(licenseLimit, 1, 50, defaultLicenseLimit);
        String severities = normalizeCveSeverities(cveSeverities);
        Double temp = temperature != null ? clampDouble(temperature, 0.0, 2.0) : null;
        Integer tokens = maxTokens != null ? clamp(maxTokens, 256, 8192, 1200) : null;
        int cap = Math.max(0, dailyCallCap);
        DeploymentProfile profile = defaultDeploymentProfile != null
                ? defaultDeploymentProfile
                : DeploymentProfile.COMMERCIAL_PRODUCT;

        AiPreferences prefs = repository.findById(AiPreferences.SINGLETON_ID)
                .orElseGet(this::defaultPreferences);
        AiEffort effort = reasoningEffort != null ? reasoningEffort : AiEffort.DEFAULT;
        prefs.update(locale, cve, lic, severities, temp, tokens, cap, promptOverrides, profile,
                effort, autoBackfillInsights);
        repository.save(prefs);
        promptTemplateService.reloadWithLocale(locale);
        log.info("[AI] Preferences saved locale={} cveLimit={} licenseLimit={} cveSeverities={} dailyCap={}"
                        + " effort={} autoBackfill={}",
                locale, cve, lic, severities, cap, effort, autoBackfillInsights);
        return prefs;
    }

    /** Backward-compatible overload for tests and simple callers. */
    @Transactional
    public AiPreferences save(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities) {
        AiPreferences current = getEffective();
        return save(promptsLocale, cveLimit, licenseLimit, cveSeverities,
                current.getTemperature(), current.getMaxTokens(), current.getDailyCallCap(),
                current.getPromptOverrides(), current.getDefaultDeploymentProfile());
    }

    /** Persisted override for the embedded sidecar directory (null = use oswl.ai.embedded.dir). */
    public String getEmbeddedDir() {
        return getEffective().getEmbeddedDir();
    }

    /** Persisted preferred embedded model file name (null = built-in preference order). */
    public String getEmbeddedModel() {
        return getEffective().getEmbeddedModel();
    }

    @Transactional
    public AiPreferences saveEmbeddedConfig(String embeddedDir, String embeddedModel) {
        AiPreferences prefs = repository.findById(AiPreferences.SINGLETON_ID)
                .orElseGet(this::defaultPreferences);
        prefs.updateEmbedded(embeddedDir, embeddedModel);
        repository.save(prefs);
        log.info("[AI] Embedded config saved dir={} model={}", prefs.getEmbeddedDir(), prefs.getEmbeddedModel());
        return prefs;
    }

    private void ensureDefaults() {
        if (repository.findById(AiPreferences.SINGLETON_ID).isEmpty()) {
            repository.save(defaultPreferences());
        }
    }

    private AiPreferences defaultPreferences() {
        return AiPreferences.defaults(
                normalizeLocale(defaultLocale),
                clamp(defaultCveLimit, 1, 50, 10),
                clamp(defaultLicenseLimit, 1, 50, 8),
                normalizeCveSeverities(defaultCveSeverities),
                defaultDailyCallCap);
    }

    private static String normalizeCveSeverities(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CRITICAL,HIGH";
        }
        List<String> tokens = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toList();
        if (tokens.isEmpty()) {
            return "CRITICAL,HIGH";
        }
        // Reject unknown severities at save time — a stored non-RiskLevel value would
        // later break every scan with IllegalArgumentException from RiskLevel.valueOf.
        for (String token : tokens) {
            try {
                RiskLevel.valueOf(token);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Invalid CVE severity '" + token + "'"
                        + " (allowed: CRITICAL, HIGH, MEDIUM, LOW, NONE)");
            }
        }
        return String.join(",", tokens);
    }

    /** Prompt-template locales that ship with an overlay bundle (ai/prompts_&lt;locale&gt;.properties). */
    private static final java.util.Set<String> SUPPORTED_PROMPT_LOCALES = java.util.Set.of("ko", "ja");

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return detectLocale();
        String value = locale.strip().toLowerCase();
        // "auto" (the default) follows the server's JVM/OS locale so a Korean or Japanese
        // machine gets localized AI prompts out of the box; anything else is en.
        if ("auto".equals(value)) return detectLocale();
        return SUPPORTED_PROMPT_LOCALES.contains(value) ? value : "en";
    }

    private static String detectLocale() {
        String lang = java.util.Locale.getDefault().getLanguage();
        return SUPPORTED_PROMPT_LOCALES.contains(lang) ? lang : "en";
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) return fallback;
        return value;
    }

    private static Double clampDouble(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
