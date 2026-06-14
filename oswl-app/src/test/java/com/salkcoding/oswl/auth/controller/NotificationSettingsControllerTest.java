package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.dto.NotificationSettingDto;
import com.salkcoding.oswl.dto.UpdateNotificationSettingRequest;
import com.salkcoding.oswl.service.NotificationSettingService;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@Tag(TestTags.AUTH)
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationSettingsController")
class NotificationSettingsControllerTest {

    @Mock NotificationSettingService notificationSettingService;
    @InjectMocks NotificationSettingsController controller;

    @Test
    @DisplayName("get returns settings DTO")
    void get_returnsDto() {
        NotificationSettingDto dto = NotificationSettingDto.builder()
                .slackConfigured(true).notifyCriticalCve(true).build();
        when(notificationSettingService.getSettings()).thenReturn(dto);

        ResponseEntity<NotificationSettingDto> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().isSlackConfigured()).isTrue();
    }

    @Test
    @DisplayName("update delegates to service")
    void update_delegates() {
        UpdateNotificationSettingRequest req = new UpdateNotificationSettingRequest();
        req.setEmailDigestEnabled(true);
        NotificationSettingDto dto = NotificationSettingDto.builder().emailDigestEnabled(true).build();
        when(notificationSettingService.updateSettings(req)).thenReturn(dto);

        ResponseEntity<NotificationSettingDto> resp = controller.update(req);

        assertThat(resp.getBody().isEmailDigestEnabled()).isTrue();
    }
}
