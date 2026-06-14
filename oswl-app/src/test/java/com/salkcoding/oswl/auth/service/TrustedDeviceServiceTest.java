package com.salkcoding.oswl.auth.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@DisplayName("TrustedDeviceService 단위 테스트")
class TrustedDeviceServiceTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            java.util.HexFormat.of().parseHex(
                    "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321".substring(0, 64)));

    private TrustedDeviceService service(String encryptionKey, String hmacKey, boolean cookieSecureOverride) {
        return new TrustedDeviceService(encryptionKey, hmacKey, cookieSecureOverride);
    }

    private TrustedDeviceService service(boolean cookieSecureOverride) {
        return service(VALID_KEY, "", cookieSecureOverride);
    }

    @Test
    @DisplayName("isEnabled: 키가 빈 문자열이면 false")
    void isEnabled_blankKey_false() {
        assertThat(service("", "", false).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("isEnabled: 유효한 키가 있으면 true")
    void isEnabled_validKey_true() {
        assertThat(service(false).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isEnabled: dedicated HMAC 키만 있어도 true")
    void isEnabled_dedicatedHmacOnly_true() {
        assertThat(service("", VALID_KEY, false).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isTrusted: 서비스가 비활성화 상태이면 항상 false")
    void isTrusted_disabled_alwaysFalse() {
        assertThat(service("", "", false).isTrusted(1L, new MockHttpServletRequest())).isFalse();
    }

    @Test
    @DisplayName("isTrusted: 쿠키가 없으면 false")
    void isTrusted_noCookie_false() {
        assertThat(service(false).isTrusted(1L, new MockHttpServletRequest())).isFalse();
    }

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
    @DisplayName("dedicated HMAC 키로 서명한 쿠키는 encryption 키로는 검증되지 않음")
    void isTrusted_dedicatedKey_notValidWithEncryptionKeyOnly() {
        TrustedDeviceService issuer = service(VALID_KEY, OTHER_KEY, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        issuer.setTrusted(1L, request, response);
        request.setCookies(response.getCookie("OSWL_TD"));

        TrustedDeviceService verifier = service(VALID_KEY, "", false);
        assertThat(verifier.isTrusted(1L, request)).isFalse();

        TrustedDeviceService correctVerifier = service(VALID_KEY, OTHER_KEY, false);
        assertThat(correctVerifier.isTrusted(1L, request)).isTrue();
    }

    @Test
    @DisplayName("setTrusted: HTTPS 요청이면 Secure 쿠키")
    void setTrusted_secureRequest_setsSecureFlag() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service(false).setTrusted(42L, request, response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
    }

    @Test
    @DisplayName("setTrusted 후 isTrusted: 다른 userId는 신뢰되지 않음")
    void setTrusted_then_isTrusted_differentUser() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();

        service(false).setTrusted(42L, request, response);
        request.setCookies(response.getCookie("OSWL_TD"));

        assertThat(service(false).isTrusted(99L, request)).isFalse();
    }

    @Test
    @DisplayName("clearTrusted: Max-Age=0인 쿠키를 응답에 추가한다")
    void clearTrusted_setsMaxAgeZero() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service(false).clearTrusted(new MockHttpServletRequest(), response);

        Cookie cookie = response.getCookie("OSWL_TD");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    @DisplayName("isTrusted: 변조된 쿠키 값이면 false")
    void isTrusted_tamperedCookie_false() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OSWL_TD", "tampered-value!!"));
        assertThat(service(false).isTrusted(1L, request)).isFalse();
    }
}
