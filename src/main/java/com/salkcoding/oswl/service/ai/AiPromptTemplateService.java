package com.salkcoding.oswl.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Korean overlay: {@code classpath:ai/prompts_ko.properties} when {@code oswl.ai.prompts.locale=ko}
 * Override path: {@code oswl.ai.prompts.location}
 */
@Slf4j
@Service
public class AiPromptTemplateService {

    private static final String DEFAULT_LOCATION = "classpath:ai/prompts.properties";
    private static final String KO_OVERLAY = "classpath:ai/prompts_ko.properties";

    private final ResourceLoader resourceLoader;
    private final String promptsLocation;

    private volatile String locale = "en";

    private Properties templates = new Properties();

    public AiPromptTemplateService(
            ResourceLoader resourceLoader,
            @Value("${oswl.ai.prompts.location:" + DEFAULT_LOCATION + "}") String promptsLocation) {
        this.resourceLoader = resourceLoader;
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
        if ("ko".equalsIgnoreCase(activeLocale)) {
            Resource ko = resourceLoader.getResource(KO_OVERLAY);
            if (ko.exists()) {
                overlay(loadFrom(ko));
                log.info("[AI] Applied Korean prompt overlay from {}", ko);
            }
        }
    }

    public void reload() {
        loadWithLocale(locale);
    }

    public String getSystemPrompt() {
        return require("system.default");
    }

    public double getTemperature() {
        return parseDouble(require("params.temperature"), 0.15);
    }

    public int getMaxTokens() {
        return (int) parseDouble(require("params.maxTokens"), 1200);
    }

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

    public String cveSingleRich(String cveId, String severity, double cvssScore, String component,
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

    public String testConnection() {
        return require("test.connection");
    }

    public String batchCvePrompt(List<AiAnalysisService.CveSummaryRequest> items) {
        StringBuilder sb = new StringBuilder(require("batch.cve.header"));
        for (int i = 0; i < items.size(); i++) {
            AiAnalysisService.CveSummaryRequest r = items.get(i);
            sb.append(render("batch.cve.item", vars(
                    "index", i + 1,
                    "id", r.id(),
                    "component", r.component(),
                    "severity", r.severity(),
                    "cvssScore", formatCvss(r.cvssScore()),
                    "title", AiEnrichmentContextBuilder.orDash(r.title()),
                    "osvSummary", AiEnrichmentContextBuilder.orDash(r.osvSummary()),
                    "fixVersion", AiEnrichmentContextBuilder.orDash(r.fixVersion()),
                    "cweId", AiEnrichmentContextBuilder.orDash(r.cweId()),
                    "cvssVector", AiEnrichmentContextBuilder.orDash(r.cvssVector()),
                    "dependencyType", r.dependencyType(),
                    "patchability", r.patchability())));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public String batchLicensePrompt(List<AiAnalysisService.LicenseSummaryRequest> items) {
        StringBuilder sb = new StringBuilder(require("batch.license.header"));
        for (int i = 0; i < items.size(); i++) {
            AiAnalysisService.LicenseSummaryRequest r = items.get(i);
            sb.append(render("batch.license.item", Map.of(
                    "index", i + 1,
                    "id", r.id(),
                    "licenseName", r.licenseName(),
                    "licenseStatus", r.licenseStatus(),
                    "component", r.component(),
                    "ecosystem", AiEnrichmentContextBuilder.orDash(r.ecosystem()),
                    "dependencyType", r.dependencyType(),
                    "latestVersion", AiEnrichmentContextBuilder.orDash(r.latestVersion()))));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public String render(String key, Map<String, ?> vars) {
        String template = require(key);
        String result = template;
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        if (result.contains("{")) {
            log.warn("[AI] Unresolved placeholders remain in prompt '{}'", key);
        }
        return result;
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
