package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OswlAuthenticationFailureHandler unit tests")
class OswlAuthenticationFailureHandlerTest {

    @Mock UserManagementService userManagementService;

    @InjectMocks OswlAuthenticationFailureHandler handler;

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;

    private AuthenticationException cause(String msg) {
        return new AuthenticationException(msg) {};
    }

    // -- DisabledException --

    @Test
    @DisplayName("DisabledException: redirects to /login?disabled (no failure count)")
    void disabledException_redirectsToDisabled() throws IOException {
        handler.onAuthenticationFailure(request, response, new DisabledException("disabled"));

        verify(response).sendRedirect("/login?disabled");
        verify(userManagementService, never()).handleLoginFailure(any());
    }

    // -- No email (null/blank) --

    @Test
    @DisplayName("Null email parameter: redirects to /login?error")
    void nullEmail_redirectsToLoginError() throws IOException {
        when(request.getParameter("email")).thenReturn(null);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?error");
        verify(userManagementService, never()).handleLoginFailure(any());
    }

    @Test
    @DisplayName("Blank email parameter: redirects to /login?error")
    void blankEmail_redirectsToLoginError() throws IOException {
        when(request.getParameter("email")).thenReturn("   ");

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?error");
        verify(userManagementService, never()).handleLoginFailure(any());
    }

    // -- Failure count below warn threshold --

    @Test
    @DisplayName("Failure count < 5: redirects to /login?error")
    void failureCount_belowWarn_redirectsToLoginError() throws IOException {
        when(request.getParameter("email")).thenReturn("user@test.com");
        when(userManagementService.handleLoginFailure("user@test.com")).thenReturn(3);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?error");
    }

    // -- Failure count at warn threshold --

    @Test
    @DisplayName("Failure count = 5 (warn): redirects to /login?error&warn")
    void failureCount_atWarn_redirectsToLoginErrorWarn() throws IOException {
        when(request.getParameter("email")).thenReturn("user@test.com");
        when(userManagementService.handleLoginFailure("user@test.com")).thenReturn(5);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?error&warn");
    }

    @Test
    @DisplayName("Failure count = 8 (between warn and lock): redirects to /login?error&warn")
    void failureCount_betweenWarnAndLock_redirectsToErrorWarn() throws IOException {
        when(request.getParameter("email")).thenReturn("user@test.com");
        when(userManagementService.handleLoginFailure("user@test.com")).thenReturn(8);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?error&warn");
    }

    // -- Failure count at lock threshold --

    @Test
    @DisplayName("Failure count = 10 (lock): redirects to /login?disabled")
    void failureCount_atLock_redirectsToDisabled() throws IOException {
        when(request.getParameter("email")).thenReturn("user@test.com");
        when(userManagementService.handleLoginFailure("user@test.com")).thenReturn(10);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?disabled");
    }

    @Test
    @DisplayName("Failure count > 10: still redirects to /login?disabled")
    void failureCount_aboveLock_redirectsToDisabled() throws IOException {
        when(request.getParameter("email")).thenReturn("admin@test.com");
        when(userManagementService.handleLoginFailure("admin@test.com")).thenReturn(15);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(response).sendRedirect("/login?disabled");
    }

    @Test
    @DisplayName("Email is trimmed and lowercased before handleLoginFailure")
    void emailTrimmedAndLowercased() throws IOException {
        when(request.getParameter("email")).thenReturn("  USER@Test.Com  ");
        when(userManagementService.handleLoginFailure("user@test.com")).thenReturn(1);

        handler.onAuthenticationFailure(request, response, cause("bad credentials"));

        verify(userManagementService).handleLoginFailure("user@test.com");
    }
}
