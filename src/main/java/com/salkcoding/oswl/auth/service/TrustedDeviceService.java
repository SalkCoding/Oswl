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
 * Issues and validates an HMAC-signed "trusted device" cookie for 30-day 2FA bypass.
 *
 * <p>Uses {@code oswl.security.trusted-device.hmac-key} when set; otherwise falls back to
 * {@code oswl.encryption.key} for backward compatibility. Prefer a dedicated HMAC key in production.
 */
@Slf4j
@Service
public class TrustedDeviceService {

    private static final String COOKIE_NAME = "OSWL_TD";
    private static final int MAX_AGE_SECONDS = 30 * 24 * 3600;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] keyBytes;
    private final boolean cookieSecureOverride;

    public TrustedDeviceService(
            @Value("${oswl.encryption.key:}") String encryptionKeyBase64,
            @Value("${oswl.security.trusted-device.hmac-key:}") String trustedDeviceHmacKeyBase64,
            @Value("${oswl.security.trusted-device-cookie-secure:false}") boolean cookieSecureOverride) {

        this.cookieSecureOverride = cookieSecureOverride;
        String keySource = selectKeySource(encryptionKeyBase64, trustedDeviceHmacKeyBase64);
        if (keySource == null) {
            log.warn("[TrustedDevice] No HMAC key configured — trusted-device functionality disabled.");
            this.keyBytes = null;
        } else {
            this.keyBytes = Base64.getDecoder().decode(keySource);
            if (trustedDeviceHmacKeyBase64 != null && !trustedDeviceHmacKeyBase64.isBlank()) {
                log.debug("[TrustedDevice] Using dedicated trusted-device HMAC key.");
            }
        }
    }

    private static String selectKeySource(String encryptionKey, String dedicatedHmacKey) {
        if (dedicatedHmacKey != null && !dedicatedHmacKey.isBlank()) {
            return dedicatedHmacKey;
        }
        if (encryptionKey != null && !encryptionKey.isBlank()) {
            return encryptionKey;
        }
        return null;
    }

    public boolean isEnabled() {
        return keyBytes != null;
    }

    public boolean isTrusted(Long userId, HttpServletRequest request) {
        if (!isEnabled()) return false;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;

        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                boolean trusted = validate(userId, c.getValue());
                if (trusted) log.debug("[TrustedDevice] Trusted-device access granted for userId={}", userId);
                return trusted;
            }
        }
        return false;
    }

    public void setTrusted(Long userId, HttpServletRequest request, HttpServletResponse response) {
        if (!isEnabled()) return;

        long expiry = System.currentTimeMillis() + ((long) MAX_AGE_SECONDS * 1000);
        String payload = userId + ":" + expiry;
        String hmac = hmac(payload);
        String value = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + hmac).getBytes(StandardCharsets.UTF_8));

        writeCookie(response, value, MAX_AGE_SECONDS, isSecure(request));
        log.debug("[TrustedDevice] Issued cookie for userId={} maxAge={}days secure={}",
                userId, MAX_AGE_SECONDS / 86400, isSecure(request));
    }

    public void clearTrusted(HttpServletRequest request, HttpServletResponse response) {
        writeCookie(response, "", 0, isSecure(request));
    }

    private boolean isSecure(HttpServletRequest request) {
        return cookieSecureOverride || (request != null && request.isSecure());
    }

    private void writeCookie(HttpServletResponse response, String value, int maxAge, boolean secure) {
        StringBuilder header = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(value)
                .append("; Path=/; Max-Age=").append(maxAge)
                .append("; HttpOnly; SameSite=Strict");
        if (secure) {
            header.append("; Secure");
        }
        response.addHeader("Set-Cookie", header.toString());

        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setSecure(secure);
        response.addCookie(cookie);
    }

    private boolean validate(Long userId, String cookieValue) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cookieValue);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int lastColon = raw.lastIndexOf(':');
            if (lastColon < 0) return false;

            String payload = raw.substring(0, lastColon);
            String suppliedHmac = raw.substring(lastColon + 1);

            String[] parts = payload.split(":", 2);
            if (parts.length != 2) return false;

            long cookieUserId = Long.parseLong(parts[0]);
            long expiryMs = Long.parseLong(parts[1]);

            if (cookieUserId != userId) return false;
            if (System.currentTimeMillis() > expiryMs) return false;

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
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < ba.length; i++) result |= ba[i] ^ bb[i];
        return result == 0;
    }
}
