package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.UserManagementService;
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

    @Mock AuditLogService       auditLogService;
    @Mock UserRepository        userRepository;
    @Mock UserManagementService userManagementService;

    @InjectMocks AuditEventListener listener;

    // -- onLoginSuccess --

    @Test
    @DisplayName("onLoginSuccess: updates last login time, resets failure count, logs")
    void onLoginSuccess_updatesAndLogs() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", null, List.of());
        InteractiveAuthenticationSuccessEvent event =
                new InteractiveAuthenticationSuccessEvent(auth, AuditEventListenerTest.class);

        listener.onLoginSuccess(event);

        verify(userRepository).updateLastLoginAt(eq("user@test.com"), any());
        verify(userManagementService).resetLoginFailureCount("user@test.com");
        verify(auditLogService).log("AUTH.LOGIN_SUCCESS", "AUTH", null, null, null);
    }

    // -- onLoginFailure --

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

    @Test
    @DisplayName("onLoginFailure: reason is derived from exception class name")
    void onLoginFailure_reasonDerivedFromExceptionClass() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", null);
        BadCredentialsException cause = new BadCredentialsException("bad creds");
        AbstractAuthenticationFailureEvent event =
                new AbstractAuthenticationFailureEvent(auth, cause) {};

        listener.onLoginFailure(event);

        // BadCredentialsException -> remove "Exception" -> "BadCredentials", remove "Authentication" -> "BadCredentials"
        verify(auditLogService).logAnonymous(any(), any(), any(), any(), any(),
                argThat(detail -> detail != null && detail.contains("BadCredentials")));
    }
}
