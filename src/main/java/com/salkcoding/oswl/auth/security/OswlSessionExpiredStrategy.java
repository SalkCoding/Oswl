package com.salkcoding.oswl.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Called by {@link org.springframework.security.web.session.ConcurrentSessionFilter}
 * when a session is expired because the same account logged in from another location.
 *
 * Redirects browser clients to /login?displaced=true&from=<ip>.
 * Returns 401 JSON for API/AJAX clients.
 */
@Component
@RequiredArgsConstructor
public class OswlSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    private final LastLoginIpStore lastLoginIpStore;

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletRequest  request  = event.getRequest();
        HttpServletResponse response = event.getResponse();

        // Determine displacing IP from the last successful login for this user
        Object principal = event.getSessionInformation().getPrincipal();
        String email = principal instanceof UserDetails ud ? ud.getUsername() : String.valueOf(principal);
        String displacingIp = lastLoginIpStore.get(email);

        // For API / AJAX requests return 401 JSON
        String accept = request.getHeader("Accept");
        String uri    = request.getRequestURI();
        if (uri.startsWith("/api/") || (accept != null && accept.contains("application/json"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Session expired — concurrent login detected\",\"status\":401}");
            return;
        }

        // Build redirect URL
        String redirectUrl = "/login?displaced=true";
        if (displacingIp != null && !displacingIp.isBlank()) {
            redirectUrl += "&from=" + URLEncoder.encode(displacingIp, StandardCharsets.UTF_8);
        }
        response.sendRedirect(redirectUrl);
    }
}
