package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginIpCaptureListener unit tests")
class LoginIpCaptureListenerTest {

    @Mock LastLoginIpStore lastLoginIpStore;

    @InjectMocks LoginIpCaptureListener listener;

    private AuthenticationSuccessEvent eventWithDetails(String email, Object details) {
        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(email, null, List.of());
        token.setDetails(details);
        return new AuthenticationSuccessEvent(token);
    }

    // -- Happy path: stores IP --

    @Test
    @DisplayName("Normal IP stored for email")
    void normalIp_storedForEmail() {
        WebAuthenticationDetails details = mock(WebAuthenticationDetails.class);
        when(details.getRemoteAddress()).thenReturn("10.20.30.40");

        AuthenticationSuccessEvent event = eventWithDetails("user@test.com", details);
        listener.onAuthSuccess(event);

        verify(lastLoginIpStore).put("user@test.com", "10.20.30.40");
    }

    // -- IPv6 loopback normalization --

    @Test
    @DisplayName("::1 normalized to 127.0.0.1")
    void ipv6Loopback_shortForm_normalized() {
        WebAuthenticationDetails details = mock(WebAuthenticationDetails.class);
        when(details.getRemoteAddress()).thenReturn("::1");

        listener.onAuthSuccess(eventWithDetails("a@b.com", details));

        verify(lastLoginIpStore).put("a@b.com", "127.0.0.1");
    }

    @Test
    @DisplayName("0:0:0:0:0:0:0:1 normalized to 127.0.0.1")
    void ipv6Loopback_fullForm_normalized() {
        WebAuthenticationDetails details = mock(WebAuthenticationDetails.class);
        when(details.getRemoteAddress()).thenReturn("0:0:0:0:0:0:0:1");

        listener.onAuthSuccess(eventWithDetails("a@b.com", details));

        verify(lastLoginIpStore).put("a@b.com", "127.0.0.1");
    }

    // -- No-op cases --

    @Test
    @DisplayName("Non-WebAuthenticationDetails: no store call")
    void nonWebDetails_ignored() {
        AuthenticationSuccessEvent event = eventWithDetails("user@test.com", "not-web-details");
        listener.onAuthSuccess(event);

        verify(lastLoginIpStore, never()).put(any(), any());
    }

    @Test
    @DisplayName("Null remote address: no store call")
    void nullRemoteAddress_ignored() {
        WebAuthenticationDetails details = mock(WebAuthenticationDetails.class);
        when(details.getRemoteAddress()).thenReturn(null);

        listener.onAuthSuccess(eventWithDetails("user@test.com", details));

        verify(lastLoginIpStore, never()).put(any(), any());
    }

    @Test
    @DisplayName("Blank remote address: no store call")
    void blankRemoteAddress_ignored() {
        WebAuthenticationDetails details = mock(WebAuthenticationDetails.class);
        when(details.getRemoteAddress()).thenReturn("  ");

        listener.onAuthSuccess(eventWithDetails("user@test.com", details));

        verify(lastLoginIpStore, never()).put(any(), any());
    }

    @Test
    @DisplayName("Null details: no store call")
    void nullDetails_ignored() {
        AuthenticationSuccessEvent event = eventWithDetails("user@test.com", null);
        listener.onAuthSuccess(event);

        verify(lastLoginIpStore, never()).put(any(), any());
    }
}
