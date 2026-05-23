package com.salkcoding.oswl.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrustedDeviceService 단위 테스트")
class TrustedDeviceServiceTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    // ── isEnabled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("isEnabled: 키가 빈 문자열이면 false")
    void isEnabled_blankKey_false() {
        TrustedDeviceService service = new TrustedDeviceService("");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("isEnabled: 유효한 키가 있으면 true")
    void isEnabled_validKey_true() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);
        assertThat(service.isEnabled()).isTrue();
    }

    // ── isTrusted (disabled) ──────────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 서비스가 비활성화 상태이면 항상 false")
    void isTrusted_disabled_alwaysFalse() {
        TrustedDeviceService service = new TrustedDeviceService("");
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service.isTrusted(1L, request)).isFalse();
    }

    // ── isTrusted (no cookie) ─────────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 쿠키가 없으면 false")
    void isTrusted_noCookie_false() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service.isTrusted(1L, request)).isFalse();
    }

    // ── setTrusted → isTrusted roundtrip ──────────────────────────────────

    @Test
    @DisplayName("setTrusted 후 isTrusted: 동일 userId로 신뢰됨")
    void setTrusted_then_isTrusted_sameUser() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.setTrusted(42L, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);

        assertThat(service.isTrusted(42L, request)).isTrue();
    }

    @Test
    @DisplayName("setTrusted 후 isTrusted: 다른 userId는 신뢰되지 않음")
    void setTrusted_then_isTrusted_differentUser() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.setTrusted(42L, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);

        assertThat(service.isTrusted(99L, request)).isFalse();
    }

    // ── clearTrusted ──────────────────────────────────────────────────────

    @Test
    @DisplayName("clearTrusted: Max-Age=0인 쿠키를 응답에 추가한다")
    void clearTrusted_setsMaxAgeZero() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.clearTrusted(response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(0);
    }

    // ── isTrusted: tampered cookie ─────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 변조된 쿠키 값이면 false")
    void isTrusted_tamperedCookie_false() {
        TrustedDeviceService service = new TrustedDeviceService(VALID_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OSWL_TD", "tampered-value!!"));

        assertThat(service.isTrusted(1L, request)).isFalse();
    }
}
