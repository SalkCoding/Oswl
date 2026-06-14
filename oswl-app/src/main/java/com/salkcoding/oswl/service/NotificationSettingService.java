package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.NotificationSetting;
import com.salkcoding.oswl.dto.NotificationSettingDto;
import com.salkcoding.oswl.dto.UpdateNotificationSettingRequest;
import com.salkcoding.oswl.repository.NotificationSettingRepository;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private static final long SINGLETON_ID = 1L;

    private final NotificationSettingRepository repository;
    private final EncryptionService encryptionService;
    private final OutboundUrlValidator outboundUrlValidator;

    @Transactional(readOnly = true)
    public NotificationSettingDto getSettings() {
        return toDto(loadOrCreate());
    }

    @Transactional
    public NotificationSettingDto updateSettings(UpdateNotificationSettingRequest req) {
        NotificationSetting s = loadOrCreate();

        if (Boolean.TRUE.equals(req.getClearSlackWebhook())) {
            s.setSlackWebhookUrl(null);
        } else if (req.getSlackWebhookUrl() != null && !req.getSlackWebhookUrl().isBlank()) {
            String url = req.getSlackWebhookUrl().strip();
            outboundUrlValidator.validateHttpUrl(url);
            s.setSlackWebhookUrl(encryptionService.encrypt(url));
        }

        if (Boolean.TRUE.equals(req.getClearTeamsWebhook())) {
            s.setTeamsWebhookUrl(null);
        } else if (req.getTeamsWebhookUrl() != null && !req.getTeamsWebhookUrl().isBlank()) {
            String url = req.getTeamsWebhookUrl().strip();
            outboundUrlValidator.validateHttpUrl(url);
            s.setTeamsWebhookUrl(encryptionService.encrypt(url));
        }

        if (req.getEmailDigestEnabled() != null) {
            s.setEmailDigestEnabled(req.getEmailDigestEnabled());
        }
        if (req.getNotifyCriticalCve() != null) {
            s.setNotifyCriticalCve(req.getNotifyCriticalCve());
        }
        if (req.getNotifyLicenseViolation() != null) {
            s.setNotifyLicenseViolation(req.getNotifyLicenseViolation());
        }

        repository.save(s);
        return toDto(s);
    }

    @Transactional
    public NotificationSetting loadOrCreate() {
        return repository.findById(SINGLETON_ID).orElseGet(() -> repository.save(NotificationSetting.builder()
                .id(SINGLETON_ID)
                .emailDigestEnabled(false)
                .notifyCriticalCve(true)
                .notifyLicenseViolation(true)
                .build()));
    }

    String decryptUrl(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private NotificationSettingDto toDto(NotificationSetting s) {
        return NotificationSettingDto.builder()
                .slackConfigured(s.getSlackWebhookUrl() != null && !s.getSlackWebhookUrl().isBlank())
                .teamsConfigured(s.getTeamsWebhookUrl() != null && !s.getTeamsWebhookUrl().isBlank())
                .emailDigestEnabled(s.isEmailDigestEnabled())
                .notifyCriticalCve(s.isNotifyCriticalCve())
                .notifyLicenseViolation(s.isNotifyLicenseViolation())
                .slackWebhookUrl(null)
                .teamsWebhookUrl(null)
                .build();
    }
}
