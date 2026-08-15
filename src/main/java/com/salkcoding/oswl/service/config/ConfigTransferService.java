package com.salkcoding.oswl.service.config;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.CacheManagementService;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.entity.org.Organization;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.entity.policy.Policy;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.PolicyScopeType;
import com.salkcoding.oswl.dto.config.ConfigBundle;
import com.salkcoding.oswl.dto.config.ConfigBundle.AiSettingExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.CacheSettingExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.LicensePolicyExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.PolicyExport;
import com.salkcoding.oswl.dto.config.ConfigBundle.RoleTemplateExport;
import com.salkcoding.oswl.dto.config.ConfigImportResult;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.repository.license.LicensePolicyRepository;
import com.salkcoding.oswl.repository.org.OrganizationRepository;
import com.salkcoding.oswl.repository.org.TeamRepository;
import com.salkcoding.oswl.repository.policy.PolicyRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import com.salkcoding.oswl.service.ai.AiPromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports/imports a portable subset of instance configuration as a single JSON bundle —
 * role templates, license policy overrides, AI provider settings minus
 * secrets, cache TTL policy, and the org/team/project policy hierarchy.
 * Meant for staging→prod promotion and air-gapped transfer.
 *
 * Policies cross instances with <em>name-based</em> scope matching (numeric ids are not
 * portable): the singleton organization always matches, teams and active projects must
 * match exactly one row by exact name. Unresolvable or ambiguous scopes are skipped and
 * reported in {@code manualStepsRequired} — never guessed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTransferService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RoleTemplateService roleTemplateService;
    private final RoleTemplateRepository roleTemplateRepository;
    private final LicensePolicyRepository licensePolicyRepository;
    private final com.salkcoding.oswl.service.license.LicensePolicyService licensePolicyService;
    private final AiSettingRepository aiSettingRepository;
    private final com.salkcoding.oswl.repository.ai.AiPreferencesRepository aiPreferencesRepository;
    private final AiPreferencesService aiPreferencesService;
    private final CacheManagementService cacheManagementService;
    private final AuditLogService auditLogService;
    private final PolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final ProjectRepository projectRepository;

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

        Map<String, String> promptOverrides = parseOverridesJson(
                aiPreferencesRepository.findById(AiPreferences.SINGLETON_ID)
                        .map(AiPreferences::getPromptOverrides).orElse(null));
        if (promptOverrides.isEmpty()) {
            promptOverrides = null;
        }

        List<CacheSettingExport> cacheSettings = cacheManagementService.findAll().stream()
                .map(c -> new CacheSettingExport(c.getCacheKey(), c.getTtlSeconds()))
                .toList();

        List<PolicyExport> policies = policyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> new PolicyExport(p.getScope().name(), exportScopeName(p),
                        p.getName(), p.getDescription(), p.isLocked(), p.isEnabled(),
                        p.getFailOnSeverity(), p.getFailOnKev(), p.getFailOnEpss(),
                        p.getFailOnLicenseViolation(), p.getOnlyNew(),
                        p.getOnlyReachable(), p.getFailOnSecrets()))
                .toList();

        List<String> redacted = new ArrayList<>();
        if (!aiSettings.isEmpty()) {
            redacted.add("aiSettings[*].apiKey — re-enter and re-activate each provider in Settings → AI after import");
        }

        return new ConfigBundle(Instant.now().toString(), null,
                roleTemplates, licensePolicy, aiSettings, promptOverrides, cacheSettings, policies, redacted);
    }

    /** Scope as a name, never an id — ids are not portable across instances. */
    private static String exportScopeName(Policy p) {
        return switch (p.getScope()) {
            case ORGANIZATION -> p.getOrganization() != null ? p.getOrganization().getName() : null;
            case TEAM -> p.getTeam() != null ? p.getTeam().getName() : null;
            case PROJECT -> p.getProject() != null ? p.getProject().getName() : null;
        };
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

        // Prompt overrides merge key-by-key (bundle wins per key); keys outside the editable
        // set and blank values are dropped, never guessed at.
        int promptsCreated = 0, promptsUpdated = 0;
        Map<String, String> bundleOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : (bundle.promptOverrides() != null
                ? bundle.promptOverrides() : Map.<String, String>of()).entrySet()) {
            if (AiPromptTemplateService.EDITABLE_PROMPT_KEYS.contains(e.getKey())
                    && e.getValue() != null && !e.getValue().isBlank()) {
                bundleOverrides.put(e.getKey(), e.getValue());
            }
        }
        if (!bundleOverrides.isEmpty()) {
            AiPreferences prefs = aiPreferencesService.getEffective();
            Map<String, String> merged = new LinkedHashMap<>(parseOverridesJson(prefs.getPromptOverrides()));
            for (Map.Entry<String, String> e : bundleOverrides.entrySet()) {
                if (merged.containsKey(e.getKey())) promptsUpdated++; else promptsCreated++;
                merged.put(e.getKey(), e.getValue());
            }
            if (!dryRun) {
                aiPreferencesService.save(prefs.getPromptsLocale(), prefs.getCveLimit(), prefs.getLicenseLimit(),
                        prefs.getCveSeverities(), prefs.getTemperature(), prefs.getMaxTokens(),
                        prefs.getDailyCallCap(), writeOverridesJson(merged), prefs.getDefaultDeploymentProfile(),
                        prefs.getReasoningEffort(), prefs.isAutoBackfillInsights());
            }
        }

        int cacheUpdated = 0;
        for (CacheSettingExport c : nullSafe(bundle.cacheSettings())) {
            cacheUpdated++;
            if (!dryRun && c.ttlSeconds() > 0) {
                cacheManagementService.updateTtl(c.cacheKey(), c.ttlSeconds());
            }
        }

        int polCreated = 0, polUpdated = 0, polUnresolved = 0;
        for (PolicyExport pol : nullSafe(bundle.policies())) {
            PolicyTarget target = resolvePolicyTarget(pol, manualSteps);
            if (target == null) {
                polUnresolved++;
                continue;
            }
            if (target.existing() != null) polUpdated++; else polCreated++;
            if (!dryRun) {
                applyPolicy(pol, target);
            }
        }

        if (!dryRun) {
            String summary = String.format(
                    "roleTemplates=%d/%d license=%d/%d aiSettings=%d/%d promptOverrides=%d/%d cache=%d policies=%d/%d unresolvedPolicies=%d (created/updated)",
                    rtCreated, rtUpdated, licCreated, licUpdated, aiCreated, aiUpdated,
                    promptsCreated, promptsUpdated, cacheUpdated,
                    polCreated, polUpdated, polUnresolved);
            auditLogService.log("CONFIG.IMPORT", "SYSTEM", null, "config-bundle", summary);
            log.info("[ConfigTransfer] Import applied: {}", summary);
        }

        return new ConfigImportResult(dryRun, rtCreated, rtUpdated, rtSkippedBuiltIn,
                licCreated, licUpdated, aiCreated, aiUpdated, promptsCreated, promptsUpdated,
                cacheUpdated, polCreated, polUpdated, polUnresolved, manualSteps);
    }

    /**
     * Matches an exported policy's scope to a target on this instance by name.
     * Returns {@code null} when the scope cannot be resolved — the caller then skips the
     * policy (a reason has already been appended to {@code manualSteps}).
     *
     * Organization scope always matches this instance's single organization row; a name
     * mismatch is only noted, since there is exactly one organization per instance.
     */
    private PolicyTarget resolvePolicyTarget(PolicyExport pol, List<String> manualSteps) {
        PolicyScopeType scopeType = parseScopeType(pol.scopeType());
        if (scopeType == null) {
            manualSteps.add(unresolvedStep(pol, "unknown scopeType '" + pol.scopeType() + "'"));
            return null;
        }
        switch (scopeType) {
            case ORGANIZATION -> {
                Organization org = organizationRepository.findFirstByOrderByIdAsc().orElse(null);
                if (org == null) {
                    manualSteps.add(unresolvedStep(pol, "no organization exists on this instance"));
                    return null;
                }
                if (pol.scopeName() != null && !pol.scopeName().equals(org.getName())) {
                    manualSteps.add("Policy '" + pol.name() + "': organization name mismatch — bundle scope is '"
                            + pol.scopeName() + "' but this instance's organization is '" + org.getName()
                            + "'; applied to this instance's organization anyway");
                }
                Policy existing = policyRepository.findByOrganizationId(org.getId()).orElse(null);
                return new PolicyTarget(org, null, null, existing);
            }
            case TEAM -> {
                Team team = pol.scopeName() != null
                        ? teamRepository.findByName(pol.scopeName()).orElse(null)
                        : null;
                if (team == null) {
                    manualSteps.add(unresolvedStep(pol, "no team named '" + pol.scopeName()
                            + "' on this instance — re-create it manually or rename the target"));
                    return null;
                }
                Policy existing = policyRepository.findByTeamId(team.getId()).orElse(null);
                return new PolicyTarget(null, team, null, existing);
            }
            case PROJECT -> {
                List<Project> matches = pol.scopeName() != null
                        ? projectRepository.findAllByNameAndDeletedAtIsNull(pol.scopeName())
                        : List.of();
                if (matches.isEmpty()) {
                    manualSteps.add(unresolvedStep(pol, "no project named '" + pol.scopeName()
                            + "' on this instance — re-create it manually or rename the target"));
                    return null;
                }
                if (matches.size() > 1) {
                    manualSteps.add(unresolvedStep(pol, matches.size() + " active projects named '"
                            + pol.scopeName() + "' on this instance — ambiguous, not applied; "
                            + "rename the target project and retry, or re-create the policy manually"));
                    return null;
                }
                Project project = matches.get(0);
                Policy existing = policyRepository.findByProjectId(project.getId()).orElse(null);
                return new PolicyTarget(null, null, project, existing);
            }
        }
        return null; // unreachable — switch is exhaustive
    }

    private static String unresolvedStep(PolicyExport pol, String reason) {
        return "Policy '" + pol.name() + "': " + reason;
    }

    private void applyPolicy(PolicyExport pol, PolicyTarget target) {
        if (target.existing() != null) {
            target.existing().update(pol.name(), pol.description(), pol.locked(), pol.enabled(),
                    pol.failOnSeverity(), pol.failOnKev(), pol.failOnEpss(),
                    pol.failOnLicenseViolation(), pol.onlyNew(),
                    pol.onlyReachable(), pol.failOnSecrets());
            policyRepository.save(target.existing());
        } else {
            policyRepository.save(Policy.builder()
                    .scope(parseScopeType(pol.scopeType()))
                    .organization(target.organization())
                    .team(target.team())
                    .project(target.project())
                    .name(pol.name())
                    .description(pol.description())
                    .locked(pol.locked())
                    .enabled(pol.enabled())
                    .failOnSeverity(pol.failOnSeverity())
                    .failOnKev(pol.failOnKev())
                    .failOnEpss(pol.failOnEpss())
                    .failOnLicenseViolation(pol.failOnLicenseViolation())
                    .onlyNew(pol.onlyNew())
                    .onlyReachable(pol.onlyReachable())
                    .failOnSecrets(pol.failOnSecrets())
                    .build());
        }
    }

    /** Resolved import target: exactly one of organization/team/project is non-null. */
    private record PolicyTarget(Organization organization, Team team, Project project, Policy existing) {}

    private static PolicyScopeType parseScopeType(String s) {
        try {
            return s != null ? PolicyScopeType.valueOf(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static Map<String, String> parseOverridesJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.warn("[ConfigTransfer] Ignoring unparseable prompt overrides JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String writeOverridesJson(Map<String, String> overrides) {
        if (overrides.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(overrides);
        } catch (Exception e) {
            log.warn("[ConfigTransfer] Failed to serialize prompt overrides: {}", e.getMessage());
            return null;
        }
    }
}
