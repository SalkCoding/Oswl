package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.NotificationSetting;
import com.salkcoding.oswl.dto.UpdateNotificationSettingRequest;
import com.salkcoding.oswl.repository.NotificationSettingRepository;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationSettingService")
class NotificationSettingServiceTest {

    @Mock NotificationSettingRepository repository;
    @Mock EncryptionService encryptionService;
    @Mock OutboundUrlValidator outboundUrlValidator;
    @InjectMocks NotificationSettingService service;

    @Test
    @DisplayName("update encrypts validated Slack webhook URL")
    void update_slackUrl_validatesAndEncrypts() {
        NotificationSetting existing = NotificationSetting.builder()
                .id(1L).emailDigestEnabled(false).notifyCriticalCve(true).notifyLicenseViolation(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("https://hooks.slack.com/services/xxx")).thenReturn("enc");

        UpdateNotificationSettingRequest req = new UpdateNotificationSettingRequest();
        req.setSlackWebhookUrl("https://hooks.slack.com/services/xxx");

        service.updateSettings(req);

        verify(outboundUrlValidator).validateHttpUrl("https://hooks.slack.com/services/xxx");
        verify(repository).save(existing);
        assertThat(existing.getSlackWebhookUrl()).isEqualTo("enc");
    }
}
