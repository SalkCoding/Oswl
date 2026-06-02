package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.controller.spec.ExternalSettingsControllerSpec;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import com.salkcoding.oswl.service.ExternalApiSettingSecretsService;
import com.salkcoding.oswl.aop.Auditable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasPermission(null, 'SETTINGS_CACHE_MANAGE') or hasRole('SYSTEM_ADMIN')")
public class ExternalSettingsController implements ExternalSettingsControllerSpec {

    private final ExternalApiSettingRepository externalApiSettingRepository;
    private final ExternalApiSettingSecretsService externalApiSettingSecretsService;

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
    @Auditable(action = "EXTERNAL_SETTING.NVD_UPDATE", targetType = "EXTERNAL_SETTING",
               targetIdExpr = "'nvd'", targetNameExpr = "'NVD API Key'",
               detailExpr = "#body['nvdApiKey'] != null && !#body['nvdApiKey'].toString().isBlank() ? 'Key updated' : 'Key cleared'")
    public ResponseEntity<Map<String, Object>> updateNvd(
            @RequestBody Map<String, String> body) {

        ExternalApiSetting s = getOrCreateSettings();
        String key = body.getOrDefault("nvdApiKey", "").strip();
        s.updateNvdApiKey(key.isEmpty() ? null : externalApiSettingSecretsService.encryptSecret(key));
        externalApiSettingRepository.save(s);

        return ResponseEntity.ok(Map.of("nvdConfigured", s.isNvdEnabled()));
    }

    // ── PUT /cache ────────────────────────────────────────────────────────

    @PutMapping("/cache")
    @Auditable(action = "EXTERNAL_SETTING.CACHE_POLICY_UPDATE", targetType = "EXTERNAL_SETTING",
               targetIdExpr = "'cache'", targetNameExpr = "'Library Cache Policy'",
               detailExpr = "#body['permanentCache'] == true ? 'Permanent cache' : 'TTL: ' + #body['cacheTtlDays'] + 'd'")
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
    @Auditable(action = "EXTERNAL_SETTING.GITHUB_UPDATE", targetType = "EXTERNAL_SETTING",
               targetIdExpr = "'github'", targetNameExpr = "'GitHub OAuth'",
               detailExpr = "'clientId=' + (#body['clientId'] != null ? #body['clientId'] : '[unchanged]')"
                          + " + '; secret=' + (#body['clientSecret'] != null && !#body['clientSecret'].isBlank() ? '[updated]' : '[unchanged]')"
                          + " + '; redirectUri=' + (#body['redirectUri'] != null ? #body['redirectUri'] : '[unchanged]')")
    public ResponseEntity<Map<String, Object>> updateGithubSettings(
            @RequestBody Map<String, String> body) {

        ExternalApiSetting s = getOrCreateSettings();
        String secret = body.get("clientSecret");
        String encryptedSecret = null;
        if (secret != null) {
            encryptedSecret = secret.isBlank() ? null : externalApiSettingSecretsService.encryptSecret(secret);
        }
        s.updateGithubOAuth(body.get("clientId"), encryptedSecret, body.get("redirectUri"));
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
