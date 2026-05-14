package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.controller.spec.AiSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
