package com.salkcoding.oswl.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.session.SessionInformationExpiredEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OswlSessionExpiredStrategy unit tests")
class OswlSessionExpiredStrategyTest {

    @Mock LastLoginIpStore lastLoginIpStore;

    @InjectMocks OswlSessionExpiredStrategy strategy;

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;

    private SessionInformationExpiredEvent buildEvent(Object principal) {
        // Create a SessionInformation with the principal
        org.springframework.security.core.session.SessionInformation si =
                new org.springframework.security.core.session.SessionInformation(
                        principal, "session-id-1", java.util.Date.from(Instant.now()));
        return new SessionInformationExpiredEvent(si, request, response);
    }

    // -- API / AJAX requests → 401 JSON --

    @Test
    @DisplayName("API URI: returns 401 JSON response")
    void apiRequest_returns401Json() throws IOException {
        StringWriter sw = new StringWriter();
        when(request.getRequestURI()).thenReturn("/api/projects");
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        User principal = new User("user@test.com", "pass", java.util.List.of());
        when(lastLoginIpStore.get("user@test.com")).thenReturn("192.168.1.1");

        strategy.onExpiredSessionDetected(buildEvent(principal));

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json;charset=UTF-8");
        assertThat(sw.toString()).contains("Session expired");
    }

    @Test
    @DisplayName("Non-API URI with Accept:application/json: returns 401 JSON")
    void ajaxRequest_returnsJson() throws IOException {
        StringWriter sw = new StringWriter();
        when(request.getRequestURI()).thenReturn("/settings");
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        User principal = new User("user@test.com", "pass", java.util.List.of());
        when(lastLoginIpStore.get("user@test.com")).thenReturn(null);

        strategy.onExpiredSessionDetected(buildEvent(principal));

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    // -- Browser requests → redirect --

    @Test
    @DisplayName("Browser request with displacing IP: redirects with from= param")
    void browserRequest_withDisplacingIp_redirectsWithFrom() throws IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(request.getHeader("Accept")).thenReturn("text/html");

        User principal = new User("user@test.com", "pass", java.util.List.of());
        when(lastLoginIpStore.get("user@test.com")).thenReturn("10.0.0.1");

        strategy.onExpiredSessionDetected(buildEvent(principal));

        verify(response).sendRedirect(argThat(url ->
                url.contains("displaced=true") && url.contains("from=10.0.0.1")));
    }

    @Test
    @DisplayName("Browser request without displacing IP: redirects without from= param")
    void browserRequest_noDisplacingIp_redirectsWithoutFrom() throws IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(request.getHeader("Accept")).thenReturn("text/html");

        User principal = new User("user@test.com", "pass", java.util.List.of());
        when(lastLoginIpStore.get("user@test.com")).thenReturn(null);

        strategy.onExpiredSessionDetected(buildEvent(principal));

        verify(response).sendRedirect("/login?displaced=true");
    }

    @Test
    @DisplayName("Non-UserDetails principal: uses toString() as email for lookup")
    void nonUserDetailsPrincipal_usesStringValue() throws IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(request.getHeader("Accept")).thenReturn("text/html");
        when(lastLoginIpStore.get("plain-string-principal")).thenReturn(null);

        strategy.onExpiredSessionDetected(buildEvent("plain-string-principal"));

        verify(lastLoginIpStore).get("plain-string-principal");
        verify(response).sendRedirect("/login?displaced=true");
    }
}
