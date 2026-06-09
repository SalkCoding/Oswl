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
 * Invoked by {@link org.springframework.security.web.session.ConcurrentSessionFilter}
 * when a session expires because the same account logged in from another location.
 *
 * Browser clients are redirected to /login?displaced=true&from=<ip>.
 * API/AJAX clients receive a 401 JSON response.
 */
@Component
@RequiredArgsConstructor
public class OswlSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    private final LastLoginIpStore lastLoginIpStore;

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletRequest  request  = event.getRequest();
        HttpServletResponse response = event.getResponse();

        // Look up the IP that displaced this user during the last successful login
        Object principal = event.getSessionInformation().getPrincipal();
        String email = principal instanceof UserDetails ud ? ud.getUsername() : String.valueOf(principal);
        String displacingIp = lastLoginIpStore.get(email);

        // Return 401 JSON for API/AJAX requests
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
