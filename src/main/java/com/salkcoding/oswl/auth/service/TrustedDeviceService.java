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
 * 30일간 2FA 두러마기를 위한 HMAC 서명 "신뢰 기기" 쿠키를 발급하고 검증한다.
 *
 * 쿠키 값 포맷: Base64(userId ":" expiryMs ":" hmac)
 *   여기서 hmac = HMAC-SHA256(userId + ":" + expiryMs, key)
 *
 * {@code oswl.encryption.key}가 없으면 서비스가 비활성화됩니다 —
 * 쿠키가 발급되지 않으며 {@link #isTrusted}는 항상 {@code false}를 반환합니다.
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
            log.warn("[TrustedDevice] 암호화 키 미설정 — 신뢰 기기 기능이 비활성화되었습니다.");
            this.keyBytes = null;
        } else {
            this.keyBytes = Base64.getDecoder().decode(base64Key);
        }
    }

    /** 서비스 활성화 여부(키 설정 시 true)를 반환한다. */
    public boolean isEnabled() {
        return keyBytes != null;
    }

    /**
     * 주어진 사용자의 신뢰 기기 쿠키를 검증한다.
     *
     * @return 쿠키가 존재하고 서명이 유효하며 만료되지 않은 경우만 true.
     */
    public boolean isTrusted(Long userId, HttpServletRequest request) {
        if (!isEnabled()) return false;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;

        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                boolean trusted = validate(userId, c.getValue());
                if (trusted) log.debug("[TrustedDevice] userId={} 두러마기 허용", userId);
                return trusted;
            }
        }
        return false;
    }

    /**
     * 주어진 사용자에게 30일짜리 신뢰 기기 쿠키를 발급한다.
     * 서비스 비활성화 시는 아무 작업도 하지 않는다.
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
        // SameSite=Strict는 Set-Cookie 헤더 속성으로 설정됨
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=" + value
                + "; Path=/; Max-Age=" + MAX_AGE_SECONDS
                + "; HttpOnly; SameSite=Strict");
        log.debug("[TrustedDevice] userId={} 쿠키 발급 maxAge={}days", userId, MAX_AGE_SECONDS / 86400);
    }

    /** 신뢰 기기 쿠키를 지운다 (예: 명시적 로그아웃 시). */
    public void clearTrusted(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────

    private boolean validate(Long userId, String cookieValue) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cookieValue);
            String raw     = new String(decoded, StandardCharsets.UTF_8);
            // raw = "userId:expiryMs:hmac"
            int lastColon  = raw.lastIndexOf(':');
            if (lastColon < 0) return false;

            String payload  = raw.substring(0, lastColon);
            String suppliedHmac = raw.substring(lastColon + 1);

            // userId 프리픽스가 일치하는지 확인
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) return false;

            long cookieUserId = Long.parseLong(parts[0]);
            long expiryMs     = Long.parseLong(parts[1]);

            if (cookieUserId != userId) return false;
            if (System.currentTimeMillis() > expiryMs) return false;

            // 상수 시간 HMAC 비교
            String expectedHmac = hmac(payload);
            return constantTimeEquals(expectedHmac, suppliedHmac);

        } catch (Exception e) {
            log.debug("[TrustedDevice] 쿠키 검증 실패: {}", e.getMessage());
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
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    /** 조기 불일치 무관하게 모든 문자를 비교하여 타이밍 코미갑 연간을 방지한다. */
    private boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < ba.length; i++) result |= ba[i] ^ bb[i];
        return result == 0;
    }
}
