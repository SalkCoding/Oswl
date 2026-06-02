package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.controller.spec.AiSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.AiPreferences;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.AiTestConnectionRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/ai")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SETTINGS_AI_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AiSettingController implements AiSettingControllerSpec {

    private final AiSettingRepository aiSettingRepository;
    private final AuditLogService auditLogService;
    private final EncryptionService encryptionService;
    private final AiAnalysisService aiAnalysisService;
    private final AiPreferencesService aiPreferencesService;

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

    @PutMapping
    @Transactional
    public ResponseEntity<AiSettingResponse> upsert(@Valid @RequestBody AiSettingUpdateRequest request) {
        AiPreferences prefs = savePreferencesIfPresent(request);

        AiSetting setting = aiSettingRepository.findByProvider(request.getProvider())
                .orElseGet(() -> AiSetting.builder().provider(request.getProvider()).build());

        String encryptedKey = request.getApiKey() != null && !request.getApiKey().isBlank()
                ? encryptionService.encrypt(request.getApiKey())
                : request.getApiKey();
        setting.update(encryptedKey, request.getModelName(), request.getBaseUrl());

        if (Boolean.TRUE.equals(request.getActivate())) {
            aiSettingRepository.findByActiveTrue().ifPresent(AiSetting::deactivate);
            setting.activate();
        }

        aiSettingRepository.save(setting);
        auditLogService.log("AI_SETTING.SAVE", "AI_SETTING",
                setting.getProvider().name(), setting.getProvider().name(),
                setting.getModelName());
        return ResponseEntity.ok(toResponse(setting, prefs));
    }

    @PutMapping("/deactivate")
    @Transactional
    public ResponseEntity<Void> deactivate(@RequestBody(required = false) AiSettingUpdateRequest request) {
        if (request != null) {
            savePreferencesIfPresent(request);
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

    private AiPreferences savePreferencesIfPresent(AiSettingUpdateRequest request) {
        if (request.getPromptsLocale() == null
                && request.getCveLimit() == null
                && request.getLicenseLimit() == null
                && request.getCveSeverities() == null) {
            return aiPreferencesService.getEffective();
        }
        AiPreferences current = aiPreferencesService.getEffective();
        String locale = request.getPromptsLocale() != null
                ? request.getPromptsLocale() : current.getPromptsLocale();
        int cveLimit = request.getCveLimit() != null
                ? request.getCveLimit() : current.getCveLimit();
        int licenseLimit = request.getLicenseLimit() != null
                ? request.getLicenseLimit() : current.getLicenseLimit();
        String cveSeverities = request.getCveSeverities() != null
                ? request.getCveSeverities() : current.getCveSeverities();
        return aiPreferencesService.save(locale, cveLimit, licenseLimit, cveSeverities);
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
                .cveSeverities(prefs.getCveSeverities());
    }

    private String maskKey(String key) {
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
