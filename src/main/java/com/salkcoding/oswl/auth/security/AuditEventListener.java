package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.LoginCompletionService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.auth.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listens for Spring Security authentication events and records audit logs.
 *
 *  - AUTH.LOGIN_SUCCESS : login success (skipped when email OTP is pending)
 *  - AUTH.LOGIN_FAILURE : login failure (including wrong password / disabled account)
 *
 * Logout events are handled by AuditLogoutSuccessHandler.
 */
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogService auditLogService;
    private final UserManagementService userManagementService;
    private final SecuritySettingService securitySettingService;
    private final LoginCompletionService loginCompletionService;

    @EventListener
    @Transactional
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        userManagementService.resetLoginFailureCount(email);

        // When email OTP is required, full login completes in OtpVerifyController or via trusted device.
        if (securitySettingService.getOrCreate().getTwoFaMode() == TwoFaMode.EMAIL_OTP) {
            return;
        }

        loginCompletionService.recordSuccessfulLogin(email);
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String attempted = event.getAuthentication().getName();
        String reason    = event.getException().getClass().getSimpleName()
                .replace("Exception", "").replace("Authentication", "");
        auditLogService.logAnonymous(attempted, "AUTH.LOGIN_FAILURE", "AUTH", null, null, "Attempted: " + attempted + " / Reason: " + reason);
    }
}
