package com.salkcoding.oswl.dto.config;

import java.util.List;

/**
 * Portable instance-config bundle — role templates, license policy overrides,
 * AI provider settings (minus secrets), and cache TTL policy. Never contains any secret,
 * API key, or password; {@code redactedFields} lists what must be re-entered by hand after import.
 *
 * Org/team/project security policies (ROADMAP A7) are intentionally NOT included here — their
 * numeric org/team/project ids are not portable across instances. Use {@code PolicyService}'s
 * own per-project YAML export/GitOps sync for those.
 */
public record ConfigBundle(
        String exportedAt,
        String appVersion,
        List<RoleTemplateExport> roleTemplates,
        List<LicensePolicyExport> licensePolicy,
        List<AiSettingExport> aiSettings,
        List<CacheSettingExport> cacheSettings,
        List<String> redactedFields
) {
    public record RoleTemplateExport(String name, String description, java.util.Set<String> permissions) {}

    public record LicensePolicyExport(String spdxId, String status, String reason) {}

    /** apiKey is never included — importing recreates the row inactive with the key blank. */
    public record AiSettingExport(String provider, String modelName, String baseUrl, boolean wasActive) {}

    public record CacheSettingExport(String cacheKey, long ttlSeconds) {}
}
