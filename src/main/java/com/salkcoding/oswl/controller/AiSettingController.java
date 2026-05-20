package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.controller.spec.AiSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.AiTestConnectionRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * AI provider settings REST endpoint.
 * Operators activate one of GPT / Claude / local LLM from the UI.
 *
 * PUT /api/settings/ai
 *   Body: { "provider": "OPENAI", "apiKey": "sk-...", "modelName": "gpt-4o", "baseUrl": null }
 *
 * PUT /api/settings/ai/activate/{provider}
 *   Switch provider (deactivates existing active setting → activates new setting)
 *
 * GET /api/settings/ai
 *   Retrieve current AI settings (apiKey is masked)
 */
@RestController
@RequestMapping("/api/settings/ai")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SETTINGS_AI_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AiSettingController implements AiSettingControllerSpec {

    private final AiSettingRepository aiSettingRepository;
    private final AuditLogService auditLogService;
    private final EncryptionService encryptionService;
    private final AiAnalysisService aiAnalysisService;

    @GetMapping
    public ResponseEntity<AiSettingResponse> getCurrent() {
        return aiSettingRepository.findByActiveTrue()
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElseGet(() -> ResponseEntity.ok(
                        AiSettingResponse.builder()
                                .message("No AI provider configured")
                                .build()));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<AiSettingResponse> upsert(@Valid @RequestBody AiSettingUpdateRequest request) {
        AiSetting setting = aiSettingRepository.findByProvider(request.getProvider())
                .orElseGet(() -> AiSetting.builder().provider(request.getProvider()).build());

        // Encrypt the API key before persisting
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
        return ResponseEntity.ok(toResponse(setting));
    }

    @PutMapping("/deactivate")
    @Transactional
    public ResponseEntity<Void> deactivate() {
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
        return ResponseEntity.ok(toResponse(setting));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(
            @Valid @RequestBody AiTestConnectionRequest request) {

        // Resolve the API key: plain-text from form takes priority;
        // if blank, fall back to the stored (encrypted) key.
        String resolvedKey;
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            resolvedKey = request.getApiKey();
        } else if (request.getProvider() == AiProvider.LOCAL) {
            resolvedKey = null; // LOCAL may not need an API key
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
                resolvedKey = stored.get().getApiKey(); // legacy plaintext fallback
            }
        }

        AiSetting tempSetting = AiSetting.builder()
                .provider(request.getProvider())
                .apiKey(resolvedKey)
                .modelName(request.getModelName())
                .baseUrl(request.getBaseUrl())
                .build();

        boolean ok = aiAnalysisService.testConnection(tempSetting);
        return ResponseEntity.ok(ok
                ? Map.of("success", true,  "message", "Connection successful!")
                : Map.of("success", false, "message", "Connection failed. Check your API key and model name."));
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private AiSettingResponse toResponse(AiSetting s) {
        return AiSettingResponse.builder()
                .provider(s.getProvider())
                .modelName(s.getModelName())
                .baseUrl(s.getBaseUrl())
                .apiKey(s.getApiKey() != null ? maskKey(s.getApiKey()) : null)
                .active(s.isActive())
                .build();
    }

    private String maskKey(String key) {
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
