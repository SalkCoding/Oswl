package com.salkcoding.oswl.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.controller.spec.AiSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.AiPreferences;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.dto.api.AiPromptsResponse;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.AiTestConnectionRequest;
import com.salkcoding.oswl.dto.api.AiUsageStatsResponse;
import com.salkcoding.oswl.dto.api.EmbeddedAiConfigRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import com.salkcoding.oswl.dto.AiConnectionTestResult;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.service.ai.AiGoldenTestService;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import com.salkcoding.oswl.service.ai.AiPromptTemplateService;
import com.salkcoding.oswl.service.ai.AiUsageStatsService;
import com.salkcoding.oswl.service.ai.EmbeddedAiProviderRegistrar;
import com.salkcoding.oswl.service.ai.EmbeddedAiService;
import com.salkcoding.oswl.service.VulnerabilityEnrichmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/settings/ai")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SETTINGS_AI_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AiSettingController implements AiSettingControllerSpec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettingRepository aiSettingRepository;
    private final AuditLogService auditLogService;
    private final EncryptionService encryptionService;
    private final AiAnalysisService aiAnalysisService;
    private final AiPreferencesService aiPreferencesService;
    private final AiPromptTemplateService promptTemplateService;
    private final OutboundUrlValidator outboundUrlValidator;
    private final AiGoldenTestService goldenTestService;
    private final AiUsageStatsService aiUsageStatsService;
    private final MessageSource messageSource;
    private final VulnerabilityEnrichmentService vulnerabilityEnrichmentService;
    private final EmbeddedAiService embeddedAiService;
    private final EmbeddedAiProviderRegistrar embeddedProviderRegistrar;

    @GetMapping
    public ResponseEntity<AiSettingResponse> getCurrent() {
        AiPreferences prefs = aiPreferencesService.getEffective();
        AiSettingResponse.AiSettingResponseBuilder builder = baseResponse(prefs);

        return aiSettingRepository.findByActiveTrue()
                .map(s -> ResponseEntity.ok(builder
                        .provider(s.getProvider())
                        .modelName(s.getModelName())
                        .baseUrl(s.getBaseUrl())
                        .apiKey(s.getApiKey() != null ? maskKey(s.getApiKey()) : null)
                        .active(s.isActive())
                        .build()))
                .orElseGet(() -> ResponseEntity.ok(builder
                        .message("No AI provider configured")
                        .build()));
    }

    @GetMapping("/usage")
    public ResponseEntity<AiUsageStatsResponse> getUsageStats() {
        return ResponseEntity.ok(aiUsageStatsService.getStats());
    }

    @GetMapping("/prompts")
    public ResponseEntity<AiPromptsResponse> getPrompts() {
        Map<String, String> snapshot = promptTemplateService.snapshot();
        Map<String, String> overrides = parseOverrides(aiPreferencesService.getEffective().getPromptOverrides());
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : AiPromptTemplateService.EDITABLE_PROMPT_KEYS) {
            if (snapshot.containsKey(key)) {
                resolved.put(key, snapshot.get(key));
            }
        }
        return ResponseEntity.ok(AiPromptsResponse.builder()
                .editableKeys(AiPromptTemplateService.EDITABLE_PROMPT_KEYS)
                .resolvedTemplates(resolved)
                .overrides(overrides)
                .build());
    }

    @PostMapping("/golden-test")
    public ResponseEntity<Map<String, Object>> runGoldenTests() {
        return ResponseEntity.ok(goldenTestService.runAll());
    }

    @PutMapping
    @Transactional
    public ResponseEntity<AiSettingResponse> upsert(@Valid @RequestBody AiSettingUpdateRequest request) {
        AiPreferences before = aiPreferencesService.getEffective();
        AiPreferences prefs = savePreferencesIfPresent(request, before);

        AiSetting setting = aiSettingRepository.findByProvider(request.getProvider())
                .orElseGet(() -> AiSetting.builder().provider(request.getProvider()).build());

        String encryptedKey = request.getApiKey() != null && !request.getApiKey().isBlank()
                ? encryptionService.encrypt(request.getApiKey())
                : request.getApiKey();
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            validateAiBaseUrl(request.getProvider(), request.getBaseUrl());
        }
        setting.update(encryptedKey, request.getModelName(), request.getBaseUrl());

        if (Boolean.TRUE.equals(request.getActivate())) {
            aiSettingRepository.findByActiveTrue().ifPresent(AiSetting::deactivate);
            setting.activate();
        }

        aiSettingRepository.save(setting);
        auditLogService.log("AI_SETTING.SAVE", "AI_SETTING",
                setting.getProvider().name(), setting.getProvider().name(),
                setting.getModelName());
        auditPreferencesIfChanged(before, prefs);
        boolean localeChanged = !Objects.equals(before.getPromptsLocale(), prefs.getPromptsLocale());
        if (localeChanged && aiSettingRepository.findByActiveTrue().isPresent()) {
            // Existing insights were generated in the previous language — regenerate them
            runAfterCommit(vulnerabilityEnrichmentService::regenerateRecentInsightsAsync);
        } else if (Boolean.TRUE.equals(request.getActivate())) {
            runAfterCommit(vulnerabilityEnrichmentService::backfillMissingInsightsAsync);
        }
        return ResponseEntity.ok(toResponse(setting, prefs));
    }

    @PutMapping("/deactivate")
    @Transactional
    public ResponseEntity<Void> deactivate(@RequestBody(required = false) AiSettingUpdateRequest request) {
        AiPreferences before = aiPreferencesService.getEffective();
        AiPreferences after;
        if (request != null) {
            after = savePreferencesIfPresent(request, before);
            auditPreferencesIfChanged(before, after);
        }
        aiSettingRepository.findByActiveTrue().ifPresent(s -> {
            auditLogService.log("AI_SETTING.DEACTIVATE", "AI_SETTING",
                    s.getProvider().name(), s.getProvider().name(), null);
            s.deactivate();
        });
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/activate/{provider}")
    @Transactional
    public ResponseEntity<AiSettingResponse> activate(@PathVariable AiProvider provider) {
        aiSettingRepository.findByActiveTrue().ifPresent(AiSetting::deactivate);

        AiSetting setting = aiSettingRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException(
                        provider + " settings not found. Configure it first via PUT /api/settings/ai."));
        setting.activate();
        auditLogService.log("AI_SETTING.ACTIVATE", "AI_SETTING",
                provider.name(), provider.name(), setting.getModelName());
        runAfterCommit(vulnerabilityEnrichmentService::backfillMissingInsightsAsync);
        return ResponseEntity.ok(toResponse(setting, aiPreferencesService.getEffective()));
    }

    /**
     * The backfill runs @Async in its own transaction. Fired inside this
     * transaction it can read the DB before the new active setting commits and
     * silently no-op — so defer it to after commit.
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ── Embedded AI (llama.cpp sidecar) ─────────────────────────────────

    @GetMapping("/embedded")
    public ResponseEntity<Map<String, Object>> embeddedStatus() {
        return ResponseEntity.ok(embeddedStatusBody());
    }

    @PostMapping("/embedded/start")
    public ResponseEntity<Map<String, Object>> startEmbedded(
            @RequestParam(required = false) String model) {
        // Launch the sidecar OUTSIDE any DB transaction — model load can block up to ~90s and
        // must not hold a database connection open.
        try {
            embeddedAiService.start(model);
        } catch (IllegalStateException e) {
            Map<String, Object> body = embeddedStatusBody();
            body.put("success", false);
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        }

        embeddedProviderRegistrar.registerAsActiveProvider(
                embeddedAiService.modelName(), embeddedAiService.baseUrl());
        auditLogService.log("AI_SETTING.EMBEDDED_START", "AI_SETTING",
                AiProvider.LOCAL.name(), AiProvider.LOCAL.name(), embeddedAiService.modelName());
        vulnerabilityEnrichmentService.backfillMissingInsightsAsync();

        Map<String, Object> body = embeddedStatusBody();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/embedded/stop")
    @Transactional
    public ResponseEntity<Map<String, Object>> stopEmbedded() {
        embeddedAiService.stop();
        // Deactivate LOCAL so AI calls don't fail against a dead endpoint
        aiSettingRepository.findByActiveTrue()
                .filter(s -> s.getProvider() == AiProvider.LOCAL)
                .ifPresent(AiSetting::deactivate);
        auditLogService.log("AI_SETTING.EMBEDDED_STOP", "AI_SETTING",
                AiProvider.LOCAL.name(), AiProvider.LOCAL.name(), null);
        Map<String, Object> body = embeddedStatusBody();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * Saves the embedded sidecar config overrides (dir / preferred model). A null field keeps
     * the current value; a blank string clears that override. When the dir actually changes
     * while the sidecar is running, the sidecar is stopped first (a running llama-server would
     * keep file locks on the old directory); a changed model takes effect on the next start.
     */
    @PutMapping("/embedded/config")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateEmbeddedConfig(
            @RequestBody(required = false) EmbeddedAiConfigRequest request) {
        String dir = request != null ? request.getDir() : null;
        String model = request != null ? request.getModel() : null;

        String normalizedDir = null;
        if (dir != null && !dir.isBlank()) {
            Path path = Path.of(dir.strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                Map<String, Object> body = embeddedStatusBody();
                body.put("success", false);
                body.put("message", msg("settings.ai.embedded.config.dirNotFound", dir.strip()));
                return ResponseEntity.badRequest().body(body);
            }
            normalizedDir = path.toString();
        }
        if (model != null && !model.isBlank()
                && (model.contains("/") || model.contains("\\") || model.contains(".."))) {
            Map<String, Object> body = embeddedStatusBody();
            body.put("success", false);
            body.put("message", msg("settings.ai.embedded.config.invalidModel"));
            return ResponseEntity.badRequest().body(body);
        }

        boolean dirChanged = normalizedDir != null
                && !normalizedDir.equals(embeddedAiService.effectiveDir().toAbsolutePath().normalize().toString());
        if (dirChanged && embeddedAiService.isRunning()) {
            // Release file locks on the old directory before switching
            embeddedAiService.stop();
            aiSettingRepository.findByActiveTrue()
                    .filter(s -> s.getProvider() == AiProvider.LOCAL)
                    .ifPresent(AiSetting::deactivate);
        }

        aiPreferencesService.saveEmbeddedConfig(
                dir == null ? aiPreferencesService.getEmbeddedDir() : normalizedDir,
                model == null ? aiPreferencesService.getEmbeddedModel()
                        : model.isBlank() ? null : model.strip());
        auditLogService.log("AI_SETTING.EMBEDDED_CONFIG", "AI_SETTING",
                AiProvider.LOCAL.name(), AiProvider.LOCAL.name(),
                "dir=" + (normalizedDir != null ? normalizedDir : "-") + " model=" + (model != null ? model : "-"));

        Map<String, Object> body = embeddedStatusBody();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> embeddedStatusBody() {
        EmbeddedAiService.EmbeddedAiStatus status = embeddedAiService.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("binaryFound", status.isBinaryFound());
        body.put("running", status.isRunning());
        body.put("binaryPath", status.getBinaryPath());
        body.put("modelFile", status.getModelFile());
        body.put("activeModel", status.getActiveModel());
        body.put("fallbackUsed", status.isFallbackUsed());
        body.put("lastError", status.getLastError());
        body.put("availableModels", status.getAvailableModels());
        body.put("baseUrl", status.getBaseUrl());
        body.put("modelsDir", status.getModelsDir());
        return body;
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(
            @Valid @RequestBody AiTestConnectionRequest request) {

        String resolvedKey;
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            resolvedKey = request.getApiKey();
        } else if (request.getProvider() == AiProvider.LOCAL) {
            resolvedKey = null;
        } else {
            var stored = aiSettingRepository.findByProvider(request.getProvider());
            if (stored.isEmpty() || stored.get().getApiKey() == null
                    || stored.get().getApiKey().isBlank()) {
                return ResponseEntity.badRequest().body(testFailBody(
                        "settings.ai.test.missingApiKey",
                        "settings.ai.test.missingApiKey.hint"));
            }
            try {
                resolvedKey = encryptionService.decrypt(stored.get().getApiKey());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(testFailBody(
                        "settings.ai.test.decryptFailed",
                        "settings.ai.test.decryptFailed.hint"));
            }
        }

        try {
            if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
                validateAiBaseUrl(request.getProvider(), request.getBaseUrl());
            }
        } catch (OutboundUrlBlockedException e) {
            auditLogService.log("AI_SETTING.TEST", "AI_SETTING",
                    request.getProvider().name(), request.getProvider().name(), "blocked-url");
            return ResponseEntity.ok(testFailBody(
                    "settings.ai.test.blockedUrl",
                    "settings.ai.test.blockedUrl.hint",
                    e.getMessage()));
        }

        AiSetting tempSetting = AiSetting.builder()
                .provider(request.getProvider())
                .apiKey(resolvedKey)
                .modelName(request.getModelName())
                .baseUrl(request.getBaseUrl())
                .build();

        AiConnectionTestResult result = aiAnalysisService.testConnectionDetailed(tempSetting);
        auditLogService.log("AI_SETTING.TEST", "AI_SETTING",
                request.getProvider().name(), request.getProvider().name(),
                result.success() ? "success" : "failed");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("message", result.message());
        if (result.hint() != null && !result.hint().isBlank()) {
            body.put("hint", result.hint());
        }
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> testFailBody(String messageKey, String hintKey, Object... args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", msg(messageKey, args));
        body.put("hint", msg(hintKey, args));
        return body;
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private AiPreferences savePreferencesIfPresent(AiSettingUpdateRequest request, AiPreferences current) {
        if (!hasPreferenceFields(request)) {
            return current;
        }
        String locale = request.getPromptsLocale() != null
                ? request.getPromptsLocale() : current.getPromptsLocale();
        int cveLimit = request.getCveLimit() != null
                ? request.getCveLimit() : current.getCveLimit();
        int licenseLimit = request.getLicenseLimit() != null
                ? request.getLicenseLimit() : current.getLicenseLimit();
        String cveSeverities = request.getCveSeverities() != null
                ? request.getCveSeverities() : current.getCveSeverities();
        Double temperature = request.getTemperature() != null
                ? request.getTemperature() : current.getTemperature();
        Integer maxTokens = request.getMaxTokens() != null
                ? request.getMaxTokens() : current.getMaxTokens();
        int dailyCap = request.getDailyCallCap() != null
                ? request.getDailyCallCap() : current.getDailyCallCap();
        String overrides = request.getPromptOverrides() != null
                ? request.getPromptOverrides() : current.getPromptOverrides();
        DeploymentProfile profile = request.getDefaultDeploymentProfile() != null
                ? DeploymentProfile.valueOf(request.getDefaultDeploymentProfile().strip())
                : current.getDefaultDeploymentProfile();
        return aiPreferencesService.save(locale, cveLimit, licenseLimit, cveSeverities,
                temperature, maxTokens, dailyCap, overrides, profile);
    }

    private void validateAiBaseUrl(AiProvider provider, String baseUrl) {
        if (provider == AiProvider.LOCAL) {
            outboundUrlValidator.validateLocalAiBaseUrl(baseUrl);
        } else {
            outboundUrlValidator.validateHttpUrl(baseUrl);
        }
    }

    private static boolean hasPreferenceFields(AiSettingUpdateRequest request) {
        return request.getPromptsLocale() != null
                || request.getCveLimit() != null
                || request.getLicenseLimit() != null
                || request.getCveSeverities() != null
                || request.getTemperature() != null
                || request.getMaxTokens() != null
                || request.getDailyCallCap() != null
                || request.getPromptOverrides() != null
                || request.getDefaultDeploymentProfile() != null;
    }

    private void auditPreferencesIfChanged(AiPreferences before, AiPreferences after) {
        if (Objects.equals(before.getPromptsLocale(), after.getPromptsLocale())
                && before.getCveLimit() == after.getCveLimit()
                && before.getLicenseLimit() == after.getLicenseLimit()
                && Objects.equals(before.getCveSeverities(), after.getCveSeverities())
                && Objects.equals(before.getTemperature(), after.getTemperature())
                && Objects.equals(before.getMaxTokens(), after.getMaxTokens())
                && before.getDailyCallCap() == after.getDailyCallCap()
                && Objects.equals(before.getPromptOverrides(), after.getPromptOverrides())
                && before.getDefaultDeploymentProfile() == after.getDefaultDeploymentProfile()) {
            return;
        }
        auditLogService.log("AI_SETTING.PREFERENCES_UPDATE", "AI_SETTING",
                "preferences", "preferences",
                "locale=" + after.getPromptsLocale() + " cveLimit=" + after.getCveLimit());
    }

    private AiSettingResponse toResponse(AiSetting s, AiPreferences prefs) {
        return baseResponse(prefs)
                .provider(s.getProvider())
                .modelName(s.getModelName())
                .baseUrl(s.getBaseUrl())
                .apiKey(s.getApiKey() != null ? maskKey(s.getApiKey()) : null)
                .active(s.isActive())
                .build();
    }

    private static AiSettingResponse.AiSettingResponseBuilder baseResponse(AiPreferences prefs) {
        return AiSettingResponse.builder()
                .promptsLocale(prefs.getPromptsLocale())
                .cveLimit(prefs.getCveLimit())
                .licenseLimit(prefs.getLicenseLimit())
                .cveSeverities(prefs.getCveSeverities())
                .temperature(prefs.getTemperature())
                .maxTokens(prefs.getMaxTokens())
                .dailyCallCap(prefs.getDailyCallCap())
                .promptOverrides(prefs.getPromptOverrides())
                .defaultDeploymentProfile(prefs.getDefaultDeploymentProfile() != null
                        ? prefs.getDefaultDeploymentProfile().name() : null);
    }

    private static Map<String, String> parseOverrides(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String maskKey(String key) {
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
