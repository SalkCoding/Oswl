package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.controller.spec.ExternalSettingsControllerSpec;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for reading and updating the external API settings
 * (NVD API key and library cache policy).
 *
 * GET  /api/settings/external          — read current config (key is never returned)
 * PUT  /api/settings/external/nvd      — update NVD API key
 * PUT  /api/settings/external/cache    — update cache policy
 */
@RestController
@RequestMapping("/api/settings/external")
@RequiredArgsConstructor
public class ExternalSettingsController implements ExternalSettingsControllerSpec {

    private final ExternalApiSettingRepository externalApiSettingRepository;

    // ── GET ───────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        ExternalApiSetting s = getOrCreateSettings();
        return ResponseEntity.ok(Map.of(
                "nvdConfigured",   s.isNvdEnabled(),
                "permanentCache",  s.isPermanentCache(),
                "cacheTtlDays",    s.getCacheTtlDays() != null ? s.getCacheTtlDays() : 0
        ));
    }

    // ── PUT /nvd ──────────────────────────────────────────────────────────

    @PutMapping("/nvd")
    public ResponseEntity<Map<String, Object>> updateNvd(
            @RequestBody Map<String, String> body) {

        ExternalApiSetting s = getOrCreateSettings();
        String key = body.getOrDefault("nvdApiKey", "").strip();
        s.updateNvdApiKey(key.isEmpty() ? null : key);
        externalApiSettingRepository.save(s);

        return ResponseEntity.ok(Map.of("nvdConfigured", s.isNvdEnabled()));
    }

    // ── PUT /cache ────────────────────────────────────────────────────────

    @PutMapping("/cache")
    public ResponseEntity<Map<String, Object>> updateCache(
            @RequestBody Map<String, Object> body) {

        ExternalApiSetting s = getOrCreateSettings();

        boolean permanent = Boolean.TRUE.equals(body.get("permanentCache"));
        Integer ttlDays = null;
        if (!permanent) {
            Object raw = body.get("cacheTtlDays");
            if (raw instanceof Number n) {
                ttlDays = n.intValue();
            }
        }

        s.updateCachePolicy(permanent, ttlDays);
        externalApiSettingRepository.save(s);

        return ResponseEntity.ok(Map.of(
                "permanentCache", s.isPermanentCache(),
                "cacheTtlDays",   s.getCacheTtlDays() != null ? s.getCacheTtlDays() : 0
        ));
    }

    // ── GET/PUT /github ───────────────────────────────────────────────────

    @GetMapping("/github")
    public ResponseEntity<Map<String, Object>> getGithubSettings() {
        ExternalApiSetting s = getOrCreateSettings();
        return ResponseEntity.ok(Map.of(
                "configured",  s.isGithubConfigured(),
                "clientId",    s.getGithubClientId()    != null ? s.getGithubClientId()    : "",
                "redirectUri", s.getGithubRedirectUri() != null ? s.getGithubRedirectUri() : ""
        ));
    }

    @PutMapping("/github")
    public ResponseEntity<Map<String, Object>> updateGithubSettings(
            @RequestBody Map<String, String> body) {

        ExternalApiSetting s = getOrCreateSettings();
        s.updateGithubOAuth(body.get("clientId"), body.get("clientSecret"), body.get("redirectUri"));
        externalApiSettingRepository.save(s);

        return ResponseEntity.ok(Map.of("configured", s.isGithubConfigured()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ExternalApiSetting getOrCreateSettings() {
        return externalApiSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> externalApiSettingRepository.save(
                        ExternalApiSetting.builder().build()));
    }
}
