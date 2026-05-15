package com.salkcoding.oswl.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Captures the remote IP of every successful authentication and stores it
 * in {@link LastLoginIpStore} keyed by the user's email address.
 *
 * This is read later by {@link OswlSessionExpiredStrategy} to inform the
 * displaced user which IP triggered their forced logout.
 */
@Component
@RequiredArgsConstructor
public class LoginIpCaptureListener {

    private final LastLoginIpStore lastLoginIpStore;

    @EventListener
    public void onAuthSuccess(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getDetails() instanceof WebAuthenticationDetails details)) {
            return;
        }
        String ip = details.getRemoteAddress();
        if (ip == null || ip.isBlank()) {
            return;
        }
        String email = event.getAuthentication().getName();
        lastLoginIpStore.put(email, ip);
    }
}
