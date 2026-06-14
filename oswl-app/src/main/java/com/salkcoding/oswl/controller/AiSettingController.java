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
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.service.ai.AiGoldenTestService;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import com.salkcoding.oswl.service.ai.AiPromptTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
        return ResponseEntity.ok(toResponse(setting, prefs));
    }

    @PutMapping("/deactivate")
    @Transactional
    public ResponseEntity<Void> deactivate(@RequestBody(required = false) AiSettingUpdateRequest request) {
        AiPreferences before = aiPreferencesService.getEffective();
        AiPreferences after = before;
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
        return ResponseEntity.ok(toResponse(setting, aiPreferencesService.getEffective()));
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
                return ResponseEntity.badRequest().body(
                        Map.of("success", false,
                               "message", "API key is not configured. Enter a key to test."));
            }
            try {
                resolvedKey = encryptionService.decrypt(stored.get().getApiKey());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false,
                               "message", "Stored API key could not be decrypted. Re-save the key in AI settings."));
            }
        }

        try {
            if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
                validateAiBaseUrl(request.getProvider(), request.getBaseUrl());
            }
        } catch (OutboundUrlBlockedException e) {
            auditLogService.log("AI_SETTING.TEST", "AI_SETTING",
                    request.getProvider().name(), request.getProvider().name(), "blocked-url");
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }

        AiSetting tempSetting = AiSetting.builder()
                .provider(request.getProvider())
                .apiKey(resolvedKey)
                .modelName(request.getModelName())
                .baseUrl(request.getBaseUrl())
                .build();

        boolean ok = aiAnalysisService.testConnection(tempSetting);
        auditLogService.log("AI_SETTING.TEST", "AI_SETTING",
                request.getProvider().name(), request.getProvider().name(),
                ok ? "success" : "failed");
        return ResponseEntity.ok(ok
                ? Map.of("success", true,  "message", "Connection successful!")
                : Map.of("success", false, "message", "Connection failed. Check your API key and model name."));
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
