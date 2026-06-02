package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.LoginCompletionService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.auth.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener unit tests")
class AuditEventListenerTest {

    @Mock AuditLogService auditLogService;
    @Mock UserManagementService userManagementService;
    @Mock SecuritySettingService securitySettingService;
    @Mock LoginCompletionService loginCompletionService;

    @InjectMocks AuditEventListener listener;

    @Test
    @DisplayName("onLoginSuccess: 2FA disabled — records full login completion")
    void onLoginSuccess_no2fa_recordsCompletion() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", null, List.of());
        InteractiveAuthenticationSuccessEvent event =
                new InteractiveAuthenticationSuccessEvent(auth, AuditEventListenerTest.class);
        when(securitySettingService.getOrCreate()).thenReturn(
                com.salkcoding.oswl.auth.entity.SecuritySetting.builder()
                        .twoFaMode(TwoFaMode.DISABLED).build());

        listener.onLoginSuccess(event);

        verify(userManagementService).resetLoginFailureCount("user@test.com");
        verify(loginCompletionService).recordSuccessfulLogin("user@test.com");
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("onLoginSuccess: email OTP enabled — resets failures only, defers login audit")
    void onLoginSuccess_emailOtp_defersCompletion() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", null, List.of());
        InteractiveAuthenticationSuccessEvent event =
                new InteractiveAuthenticationSuccessEvent(auth, AuditEventListenerTest.class);
        when(securitySettingService.getOrCreate()).thenReturn(
                com.salkcoding.oswl.auth.entity.SecuritySetting.builder()
                        .twoFaMode(TwoFaMode.EMAIL_OTP).build());

        listener.onLoginSuccess(event);

        verify(userManagementService).resetLoginFailureCount("user@test.com");
        verifyNoInteractions(loginCompletionService);
    }

    @Test
    @DisplayName("onLoginFailure: logs with attempted email and reason")
    void onLoginFailure_logsFailure() {
        Authentication auth = new UsernamePasswordAuthenticationToken("attacker@bad.com", null);
        BadCredentialsException cause = new BadCredentialsException("bad");
        AbstractAuthenticationFailureEvent event =
                new AbstractAuthenticationFailureEvent(auth, cause) {};

        listener.onLoginFailure(event);

        verify(auditLogService).logAnonymous(
                eq("attacker@bad.com"),
                eq("AUTH.LOGIN_FAILURE"),
                eq("AUTH"),
                isNull(),
                isNull(),
                contains("attacker@bad.com"));
    }
}
