package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.controller.spec.NotificationSettingsControllerSpec;
import com.salkcoding.oswl.dto.NotificationSettingDto;
import com.salkcoding.oswl.dto.UpdateNotificationSettingRequest;
import com.salkcoding.oswl.service.NotificationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/notifications")
@PreAuthorize("hasPermission(null, 'SETTINGS_NOTIFICATION_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class NotificationSettingsController implements NotificationSettingsControllerSpec {

    private final NotificationSettingService notificationSettingService;

    @GetMapping
    public ResponseEntity<NotificationSettingDto> get() {
        return ResponseEntity.ok(notificationSettingService.getSettings());
    }

    @PutMapping
    public ResponseEntity<NotificationSettingDto> update(
            @RequestBody UpdateNotificationSettingRequest request) {
        return ResponseEntity.ok(notificationSettingService.updateSettings(request));
    }
}
