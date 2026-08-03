package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.notification.WebhookSetting;
import com.salkcoding.oswl.domain.enums.WebhookProvider;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.repository.notification.WebhookSettingRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Persistence and validation for the single-row webhook settings.
 * The incoming webhook URL is encrypted at rest using the same AES-256-GCM
 * mechanism as VCS tokens and mail passwords.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookSettingService {

    private static final String CACHE_KEY = "current";

    private final WebhookSettingRepository webhookSettingRepository;
    private final EncryptionService encryptionService;
    private final OutboundUrlValidator outboundUrlValidator;
    private final AuditLogService auditLogService;

    private final Cache<String, WebhookSetting> webhookSettingCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10)
            .recordStats()
            .build();

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    @Transactional(readOnly = true)
    public WebhookSetting getSetting() {
        WebhookSetting cached = webhookSettingCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        WebhookSetting setting = webhookSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> WebhookSetting.builder()
                        .provider(WebhookProvider.SLACK)
                        .enabled(false)
                        .build());
        webhookSettingCache.put(CACHE_KEY, setting);
        return setting;
    }

    /**
     * Returns the decrypted webhook URL, or null when not configured.
     * Callers must treat the result as a secret.
     */
    @Transactional(readOnly = true)
    public String getDecryptedUrl() {
        WebhookSetting setting = getSetting();
        if (setting.getWebhookUrl() == null || setting.getWebhookUrl().isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(setting.getWebhookUrl());
        } catch (Exception e) {
            log.error("[Webhook] Stored URL could not be decrypted — re-save webhook settings.");
            return null;
        }
    }

    @Transactional
    public void save(WebhookProvider provider, String urlPlain, boolean enabled,
                     boolean notifyNewCve, boolean notifyGateFailure,
                     boolean notifyScanFailure, boolean notifyWaiverExpiry) {
        WebhookSetting setting = webhookSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> WebhookSetting.builder().build());

        String normalized = normalizeUrl(urlPlain);
        if (normalized != null && !normalized.isBlank()) {
            validateUrl(normalized);
        }

        String encryptedUrl = (urlPlain != null && !urlPlain.isBlank())
                ? encryptionService.encrypt(normalized)
                : setting.getWebhookUrl();

        setting.update(provider, encryptedUrl, enabled,
                notifyNewCve, notifyGateFailure, notifyScanFailure, notifyWaiverExpiry);
        webhookSettingRepository.save(setting);
        webhookSettingCache.invalidate(CACHE_KEY);

        auditLogService.log("WEBHOOK.SETTINGS_UPDATE", "EXTERNAL_SETTING", "webhook", null,
                "provider=" + provider + " enabled=" + enabled
                        + " newCve=" + notifyNewCve + " gate=" + notifyGateFailure
                        + " scan=" + notifyScanFailure + " waiver=" + notifyWaiverExpiry);
    }

    private void validateUrl(String url) {
        try {
            if (airgapped) {
                outboundUrlValidator.validateInternalHttpUrl(url);
            } else {
                outboundUrlValidator.validateHttpUrl(url);
            }
        } catch (OutboundUrlBlockedException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return null;
        String u = url.strip();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u;
    }
}
