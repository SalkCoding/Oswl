package com.salkcoding.oswl.auth.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP security response headers (Sprint 5 / #20).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "oswl.security.headers")
public class OswlSecurityHeadersProperties {

    /** Send Strict-Transport-Security when the request is considered HTTPS (direct or via X-Forwarded-Proto). */
    private boolean hstsEnabled = true;

    private long hstsMaxAgeSeconds = 31_536_000L;

    private boolean hstsIncludeSubDomains = true;

    private boolean hstsPreload = false;

    /**
     * Content-Security-Policy directive string. Empty disables CSP (not recommended for production).
     */
    private String contentSecurityPolicy = "default-src 'self'; "
            // Alpine.js evaluates x-data / @click expressions via AsyncFunction (requires unsafe-eval).
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; "
            + "font-src 'self' https://fonts.gstatic.com data:; "
            + "img-src 'self' data:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'self'; "
            + "base-uri 'self'; "
            + "form-action 'self'";

    /** DENY, SAMEORIGIN, or DISABLE (omit header). */
    private String frameOptions = "DENY";
}
