package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Listens for Spring Security authentication events and records audit logs.
 *
 *  - AUTH.LOGIN_SUCCESS : login success
 *  - AUTH.LOGIN_FAILURE : login failure (including wrong password / disabled account)
 *
 * Logout events are handled by AuditLogoutSuccessHandler.
 */
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final UserManagementService userManagementService;

    @EventListener
    @Transactional
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        userRepository.updateLastLoginAt(email, LocalDateTime.now());
        userManagementService.resetLoginFailureCount(email);
        auditLogService.log("AUTH.LOGIN_SUCCESS", "AUTH", null, null, null);
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String attempted = event.getAuthentication().getName();
        String reason    = event.getException().getClass().getSimpleName()
                .replace("Exception", "").replace("Authentication", "");
        auditLogService.logAnonymous(attempted, "AUTH.LOGIN_FAILURE", "AUTH", null, null, "Attempted: " + attempted + " / Reason: " + reason);
    }
}
