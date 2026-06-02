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

    private TrustedDeviceService service(boolean cookieSecureOverride) {
        return new TrustedDeviceService(VALID_KEY, cookieSecureOverride);
    }

    // ── isEnabled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("isEnabled: 키가 빈 문자열이면 false")
    void isEnabled_blankKey_false() {
        TrustedDeviceService svc = new TrustedDeviceService("", false);
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("isEnabled: 유효한 키가 있으면 true")
    void isEnabled_validKey_true() {
        assertThat(service(false).isEnabled()).isTrue();
    }

    // ── isTrusted (disabled) ──────────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 서비스가 비활성화 상태이면 항상 false")
    void isTrusted_disabled_alwaysFalse() {
        TrustedDeviceService svc = new TrustedDeviceService("", false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(svc.isTrusted(1L, request)).isFalse();
    }

    // ── isTrusted (no cookie) ─────────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 쿠키가 없으면 false")
    void isTrusted_noCookie_false() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service(false).isTrusted(1L, request)).isFalse();
    }

    // ── setTrusted → isTrusted roundtrip ──────────────────────────────────

    @Test
    @DisplayName("setTrusted 후 isTrusted: 동일 userId로 신뢰됨")
    void setTrusted_then_isTrusted_sameUser() {
        TrustedDeviceService svc = service(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        svc.setTrusted(42L, request, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();

        request.setCookies(cookie);

        assertThat(svc.isTrusted(42L, request)).isTrue();
    }

    @Test
    @DisplayName("setTrusted: HTTPS 요청이면 Secure 쿠키")
    void setTrusted_secureRequest_setsSecureFlag() {
        TrustedDeviceService svc = service(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        svc.setTrusted(42L, request, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
    }

    @Test
    @DisplayName("setTrusted 후 isTrusted: 다른 userId는 신뢰되지 않음")
    void setTrusted_then_isTrusted_differentUser() {
        TrustedDeviceService svc = service(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();

        svc.setTrusted(42L, request, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        request.setCookies(cookie);

        assertThat(svc.isTrusted(99L, request)).isFalse();
    }

    // ── clearTrusted ──────────────────────────────────────────────────────

    @Test
    @DisplayName("clearTrusted: Max-Age=0인 쿠키를 응답에 추가한다")
    void clearTrusted_setsMaxAgeZero() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service(false).clearTrusted(request, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(0);
    }

    // ── isTrusted: tampered cookie ─────────────────────────────────────────

    @Test
    @DisplayName("isTrusted: 변조된 쿠키 값이면 false")
    void isTrusted_tamperedCookie_false() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OSWL_TD", "tampered-value!!"));

        assertThat(service(false).isTrusted(1L, request)).isFalse();
    }
}
