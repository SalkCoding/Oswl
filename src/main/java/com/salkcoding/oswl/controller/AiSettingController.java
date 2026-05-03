package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.AiSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
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
@RequiredArgsConstructor
public class AiSettingController implements AiSettingControllerSpec {

    private final AiSettingRepository aiSettingRepository;

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

        setting.update(request.getApiKey(), request.getModelName(), request.getBaseUrl());

        if (Boolean.TRUE.equals(request.getActivate())) {
            aiSettingRepository.findByActiveTrue().ifPresent(AiSetting::deactivate);
            setting.activate();
        }

        aiSettingRepository.save(setting);
        return ResponseEntity.ok(toResponse(setting));
    }

    @PutMapping("/activate/{provider}")
    @Transactional
    public ResponseEntity<AiSettingResponse> activate(@PathVariable AiProvider provider) {
        aiSettingRepository.findByActiveTrue().ifPresent(AiSetting::deactivate);

        AiSetting setting = aiSettingRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException(
                        provider + " settings not found. Configure it first via PUT /api/settings/ai."));
        setting.activate();

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
