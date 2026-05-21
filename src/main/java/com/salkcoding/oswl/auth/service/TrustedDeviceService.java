package com.salkcoding.oswl.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Issues and validates HMAC-signed "trusted device" cookies for 30-day 2FA bypass.
 *
 * Cookie value format: Base64(userId ":" expiryMs ":" hmac)
 *   where hmac = HMAC-SHA256(userId + ":" + expiryMs, key)
 *
 * If {@code oswl.encryption.key} is absent the service is disabled — no cookies are
 * issued and {@link #isTrusted} always returns {@code false}.
 */
@Slf4j
@Service
public class TrustedDeviceService {

    private static final String  COOKIE_NAME      = "OSWL_TD";
    private static final int     MAX_AGE_SECONDS  = 30 * 24 * 3600; // 30 days
    private static final String  HMAC_ALGORITHM   = "HmacSHA256";

    private final byte[] keyBytes;

    public TrustedDeviceService(
            @Value("${oswl.encryption.key:}") String base64Key) {

        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[TrustedDevice] No encryption key configured — trusted-device feature is disabled.");
            this.keyBytes = null;
        } else {
            this.keyBytes = Base64.getDecoder().decode(base64Key);
        }
    }

    /** Returns true if the feature is enabled (key is configured). */
    public boolean isEnabled() {
        return keyBytes != null;
    }

    /**
     * Validates the trusted-device cookie for the given user.
     *
     * @return true only when the cookie is present, the signature is valid and it has not expired.
     */
    public boolean isTrusted(Long userId, HttpServletRequest request) {
        if (!isEnabled()) return false;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;

        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                boolean trusted = validate(userId, c.getValue());
                if (trusted) log.debug("[TrustedDevice] Bypass granted for userId={}", userId);
                return trusted;
            }
        }
        return false;
    }

    /**
     * Issues a 30-day trusted-device cookie for the given user.
     * Does nothing if the feature is disabled.
     */
    public void setTrusted(Long userId, HttpServletResponse response) {
        if (!isEnabled()) return;

        long expiry   = System.currentTimeMillis() + ((long) MAX_AGE_SECONDS * 1000);
        String payload = userId + ":" + expiry;
        String hmac    = hmac(payload);
        String value   = Base64.getUrlEncoder().withoutPadding()
                               .encodeToString((payload + ":" + hmac).getBytes(StandardCharsets.UTF_8));

        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(MAX_AGE_SECONDS);
        // SameSite=Strict is set via the Set-Cookie header attribute
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=" + value
                + "; Path=/; Max-Age=" + MAX_AGE_SECONDS
                + "; HttpOnly; SameSite=Strict");
        log.debug("[TrustedDevice] Cookie issued for userId={} maxAge={}days", userId, MAX_AGE_SECONDS / 86400);
    }

    /** Clears the trusted-device cookie (e.g., on explicit logout). */
    public void clearTrusted(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private boolean validate(Long userId, String cookieValue) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cookieValue);
            String raw     = new String(decoded, StandardCharsets.UTF_8);
            // raw = "userId:expiryMs:hmac"
            int lastColon  = raw.lastIndexOf(':');
            if (lastColon < 0) return false;

            String payload  = raw.substring(0, lastColon);
            String suppliedHmac = raw.substring(lastColon + 1);

            // Verify userId prefix matches
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) return false;

            long cookieUserId = Long.parseLong(parts[0]);
            long expiryMs     = Long.parseLong(parts[1]);

            if (cookieUserId != userId) return false;
            if (System.currentTimeMillis() > expiryMs) return false;

            // Constant-time HMAC comparison
            String expectedHmac = hmac(payload);
            return constantTimeEquals(expectedHmac, suppliedHmac);

        } catch (Exception e) {
            log.debug("[TrustedDevice] Cookie validation failed: {}", e.getMessage());
            return false;
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /** Prevent timing attacks by comparing all characters regardless of early mismatch. */
    private boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < ba.length; i++) result |= ba[i] ^ bb[i];
        return result == 0;
    }
}
