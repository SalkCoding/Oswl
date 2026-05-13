package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.aop.Auditable;
import com.salkcoding.oswl.auth.dto.MailTestRequest;
import com.salkcoding.oswl.auth.dto.SecuritySettingResponse;
import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings/security")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'SETTINGS_SECURITY_MANAGE') or hasRole('SYSTEM_ADMIN')")
public class SecuritySettingsController {

    private final SecuritySettingService securitySettingService;

    @GetMapping
    public ResponseEntity<SecuritySettingResponse> get() {
        return ResponseEntity.ok(
                securitySettingService.toResponse(securitySettingService.getOrCreate()));
    }

    @PutMapping
    @Auditable(action = "SECURITY_SETTING.UPDATE", targetType = "SECURITY_SETTING")
    public ResponseEntity<SecuritySettingResponse> update(
            @RequestBody SecuritySettingUpdateRequest req) {
        return ResponseEntity.ok(
                securitySettingService.toResponse(securitySettingService.update(req)));
    }

    @PostMapping("/mail/test")
    public ResponseEntity<Map<String, Object>> testMail(@RequestBody MailTestRequest req) {
        try {
            securitySettingService.testMailConnection(req);
            return ResponseEntity.ok(Map.of("message", "Connection successful."));
        } catch (MessagingException e) {
            String detail = e.getMessage() != null ? e.getMessage() : "SMTP connection failed.";
            return ResponseEntity.badRequest().body(Map.of("message", detail));
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : "Unexpected error.";
            return ResponseEntity.internalServerError().body(Map.of("message", detail));
        }
    }
}
