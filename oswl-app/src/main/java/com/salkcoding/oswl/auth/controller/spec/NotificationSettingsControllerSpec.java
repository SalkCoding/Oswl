package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.dto.NotificationSettingDto;
import com.salkcoding.oswl.dto.UpdateNotificationSettingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Notification Settings", description = "Instance-wide Slack / Teams / email digest alerts.")
@SecurityRequirement(name = "sessionAuth")
public interface NotificationSettingsControllerSpec {

    @Operation(summary = "Get notification channel configuration")
    ResponseEntity<NotificationSettingDto> get();

    @Operation(summary = "Update notification channels and trigger flags")
    ResponseEntity<NotificationSettingDto> update(UpdateNotificationSettingRequest request);
}
