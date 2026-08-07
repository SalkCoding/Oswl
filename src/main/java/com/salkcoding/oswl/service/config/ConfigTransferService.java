package com.salkcoding.oswl.service.config;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.CacheManagementService;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.config.ConfigBundle;
import com.salkcoding.oswl.dto.config.ConfigBundle.AiSettingExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.CacheSettingExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.LicensePolicyExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.RoleTemplateExport;
import com.salkcoding.oswl.dto.config.ConfigImportResult;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.repository.license.LicensePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports/imports a portable subset of instance configuration as a single JSON bundle —
 * role templates, license policy overrides, AI provider settings minus
 * secrets, and cache TTL policy. Meant for staging→prod promotion and air-gapped transfer.
 *
 * Deliberately excludes org/team/project security policies — their ids are not
 * portable across instances; use {@code PolicyService}'s own per-project YAML/GitOps export.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTransferService {

    private final RoleTemplateService roleTemplateService;
    private final RoleTemplateRepository roleTemplateRepository;
    private final LicensePolicyRepository licensePolicyRepository;
    private final com.salkcoding.oswl.service.license.LicensePolicyService licensePolicyService;
    private final AiSettingRepository aiSettingRepository;
    private final CacheManagementService cacheManagementService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public ConfigBundle export() {
        List<RoleTemplateExport> roleTemplates = roleTemplateService.findAll().stream()
                .filter(t -> !t.isBuiltIn())
                .map(t -> new RoleTemplateExport(t.getName(), t.getDescription(), t.getPermissions()))
                .toList();

        List<LicensePolicyExport> licensePolicy = licensePolicyRepository.findAll().stream()
                .map(e -> new LicensePolicyExport(e.getSpdxId(), e.getStatus().name(), e.getReason()))
                .toList();

        List<AiSettingExport> aiSettings = aiSettingRepository.findAll().stream()
                .map(s -> new AiSettingExport(s.getProvider().name(), s.getModelName(), s.getBaseUrl(), s.isActive()))
                .toList();

        List<CacheSettingExport> cacheSettings = cacheManagementService.findAll().stream()
                .map(c -> new CacheSettingExport(c.getCacheKey(), c.getTtlSeconds()))
                .toList();

        List<String> redacted = new ArrayList<>();
        if (!aiSettings.isEmpty()) {
            redacted.add("aiSettings[*].apiKey — re-enter and re-activate each provider in Settings → AI after import");
        }

        return new ConfigBundle(Instant.now().toString(), null,
                roleTemplates, licensePolicy, aiSettings, cacheSettings, redacted);
    }

    /** {@code dryRun=true} counts what would change without writing anything. */
    @Transactional
    public ConfigImportResult importBundle(ConfigBundle bundle, boolean dryRun) {
        int rtCreated = 0, rtUpdated = 0, rtSkippedBuiltIn = 0;
        for (RoleTemplateExport rt : nullSafe(bundle.roleTemplates())) {
            RoleTemplate existing = roleTemplateRepository.findByName(rt.name()).orElse(null);
            if (existing != null && existing.isBuiltIn()) {
                rtSkippedBuiltIn++;
                continue;
            }
            if (existing != null) rtUpdated++; else rtCreated++;
            if (!dryRun) {
                roleTemplateService.upsertByName(rt.name(), rt.description(), rt.permissions());
            }
        }

        int licCreated = 0, licUpdated = 0;
        for (LicensePolicyExport lic : nullSafe(bundle.licensePolicy())) {
            boolean exists = licensePolicyRepository.findBySpdxId(lic.spdxId()).isPresent();
            if (exists) licUpdated++; else licCreated++;
            if (!dryRun) {
                LicenseStatus status = parseStatus(lic.status());
                if (status != null) {
                    licensePolicyService.upsertEntry(lic.spdxId(), status, lic.reason());
                }
            }
        }

        int aiCreated = 0, aiUpdated = 0;
        List<String> manualSteps = new ArrayList<>();
        for (AiSettingExport ai : nullSafe(bundle.aiSettings())) {
            AiProvider provider = parseProvider(ai.provider());
            if (provider == null) continue;
            AiSetting existing = aiSettingRepository.findByProvider(provider).orElse(null);
            if (existing != null) aiUpdated++; else aiCreated++;
            if (!dryRun) {
                if (existing != null) {
                    existing.update(null, ai.modelName(), ai.baseUrl());
                    aiSettingRepository.save(existing);
                } else {
                    aiSettingRepository.save(AiSetting.builder()
                            .provider(provider)
                            .modelName(ai.modelName())
                            .baseUrl(ai.baseUrl())
                            .active(false)
                            .build());
                }
            }
            manualSteps.add("AI provider " + provider + ": enter the API key and re-activate in Settings → AI");
        }

        int cacheUpdated = 0;
        for (CacheSettingExport c : nullSafe(bundle.cacheSettings())) {
            cacheUpdated++;
            if (!dryRun && c.ttlSeconds() > 0) {
                cacheManagementService.updateTtl(c.cacheKey(), c.ttlSeconds());
            }
        }

        if (!dryRun) {
            String summary = String.format(
                    "roleTemplates=%d/%d license=%d/%d aiSettings=%d/%d cache=%d (created/updated)",
                    rtCreated, rtUpdated, licCreated, licUpdated, aiCreated, aiUpdated, cacheUpdated);
            auditLogService.log("CONFIG.IMPORT", "SYSTEM", null, "config-bundle", summary);
            log.info("[ConfigTransfer] Import applied: {}", summary);
        }

        return new ConfigImportResult(dryRun, rtCreated, rtUpdated, rtSkippedBuiltIn,
                licCreated, licUpdated, aiCreated, aiUpdated, cacheUpdated, manualSteps);
    }

    private static LicenseStatus parseStatus(String s) {
        try {
            return s != null ? LicenseStatus.valueOf(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AiProvider parseProvider(String s) {
        try {
            return s != null ? AiProvider.valueOf(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }
}
