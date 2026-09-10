package com.salkcoding.oswl.dto.config;

import java.util.List;
import java.util.Map;

/**
 * Portable instance-config bundle — role templates, license policy overrides,
 * AI provider settings (minus secrets), user-customized AI prompt overrides, cache TTL
 * policy, and the org/team/project security policy hierarchy. Never contains any secret,
 * API key, or password; {@code redactedFields} lists what must be re-entered by hand after import.
 *
 * <p>Prompt overrides are the DB-stored per-key customizations made in Settings → AI
 * ({@code AiPreferences.promptOverrides}); the base templates themselves ship with the app
 * (classpath resources, locale-overlaid) and are deliberately not portable.
 *
 * Policies are exported with their scope resolved to <em>names</em> (never numeric ids,
 * which are not portable across instances). On import the scope is matched by exact name:
 * the singleton organization always matches, teams and active (non-deleted) projects must
 * match exactly one row — unresolvable or ambiguous scopes are skipped and reported in
 * {@code ConfigImportResult.manualStepsRequired} instead of being guessed.
 */
public record ConfigBundle(
        String exportedAt,
        String appVersion,
        List<RoleTemplateExport> roleTemplates,
        List<LicensePolicyExport> licensePolicy,
        List<AiSettingExport> aiSettings,
        /** User-customized AI prompt overrides ({key → template text}); base templates ship with the app. */
        Map<String, String> promptOverrides,
        List<CacheSettingExport> cacheSettings,
        List<PolicyExport> policies,
        List<String> redactedFields
) {
    public record RoleTemplateExport(String name, String description, java.util.Set<String> permissions) {}

    public record LicensePolicyExport(String spdxId, String status, String reason) {}

    /** apiKey is never included — importing recreates the row inactive with the key blank. */
    public record AiSettingExport(String provider, String modelName, String baseUrl, boolean wasActive) {}

    public record CacheSettingExport(String cacheKey, long ttlSeconds) {}

    /**
     * One policy row with its scope as scopeType + scopeName (organization/team/project name).
     * Raw org/team/project ids are never exported — they differ per instance.
     */
    public record PolicyExport(String scopeType, String scopeName, String name, String description,
                               boolean locked, boolean enabled,
                               String failOnSeverity, Boolean failOnKev, Double failOnEpss,
                               Boolean failOnLicenseViolation, Boolean onlyNew,
                               Boolean onlyReachable, Boolean failOnSecrets) {}
}
