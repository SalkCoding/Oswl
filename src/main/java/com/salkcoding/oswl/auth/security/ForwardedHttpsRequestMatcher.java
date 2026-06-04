package com.salkcoding.oswl.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches requests served over HTTPS, including behind a reverse proxy that sets {@code X-Forwarded-Proto: https}.
 */
public final class ForwardedHttpsRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            String proto = forwarded.split(",")[0].trim();
            return "https".equalsIgnoreCase(proto);
        }
        String forwardedSsl = request.getHeader("X-Forwarded-Ssl");
        return "on".equalsIgnoreCase(forwardedSsl);
    }
}
