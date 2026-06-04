package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiPreferences;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.domain.enums.RiskLevel;
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

    @Transactional
    public AiPreferences save(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities,
                            Double temperature, Integer maxTokens, int dailyCallCap,
                            String promptOverrides, DeploymentProfile defaultDeploymentProfile) {
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
        prefs.update(locale, cve, lic, severities, temp, tokens, cap, promptOverrides, profile);
        repository.save(prefs);
        promptTemplateService.reloadWithLocale(locale);
        log.info("[AI] Preferences saved locale={} cveLimit={} licenseLimit={} cveSeverities={} dailyCap={}",
                locale, cve, lic, severities, cap);
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
        String normalized = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.joining(","));
        return normalized.isEmpty() ? "CRITICAL,HIGH" : normalized;
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        String value = locale.strip().toLowerCase();
        return "ko".equals(value) ? "ko" : "en";
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
