package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;

@Import(AuthExpiryUiTest.SessionCapture.class)
class AuthExpiryUiTest extends UiTestBase {
    @Autowired SessionStore sessions;
    @Autowired OtpService otp;
    @Autowired UserDetailsService userDetails;
    @MockitoBean MailService mail; // No messages leave this test context.

    @Test void invalidatedSessionCannotSaveAnOpenSettingsForm() {
        loginAsTestAdmin(); page.navigate(url("/settings?tab=reports&lang=en"));
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data^=reportsTab]')).loaded");
        session().invalidate();
        var response = page.waitForResponse(r -> r.url().contains("/api/settings/report-branding") && r.request().method().equals("PUT"),
                () -> page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=reportsTab]')).save()"));
        assertThat(response.status()).isIn(401, 403);
    }

    @Test void serverExpiredLoginCodeShowsFailureInsteadOfAuthenticating() {
        page.navigate(url("/login?lang=en"));
        var session = session();
        otp.storePendingAuth(session, (OswlUserPrincipal) userDetails.loadUserByUsername(TEST_EMAIL));
        String code = (String) session.getAttribute(OtpService.SESSION_OTP);
        page.navigate(url("/login/otp-verify?lang=en"));
        session.setAttribute(OtpService.SESSION_EXPIRY, 0L);
        fillExpiredCode(code, "/login/otp-verify");
        assertThat(page.url()).contains("/login/otp-verify");
    }

    @Test void serverExpiredPasswordChallengeCannotAuthorizePasswordChange() {
        loginAsTestAdmin(); var session = session();
        otp.storeChangePasswordOtp(session, TEST_EMAIL, TEST_DISPLAY_NAME);
        String code = (String) session.getAttribute(OtpService.SESSION_CHANGE_PW_OTP);
        page.navigate(url("/my/change-password/otp?lang=en"));
        session.setAttribute(OtpService.SESSION_CHANGE_PW_EXPIRY, 0L);
        fillExpiredCode(code, "/api/my/change-password/otp-verify");
        assertThat(otp.isChangePasswordOtpVerified(session)).isFalse();
    }

    private void fillExpiredCode(String code, String endpoint) {
        var response = page.waitForResponse(r -> r.url().contains(endpoint) && r.request().method().equals("POST"), () -> {
            for (int i = 0; i < 6; i++) page.fill("#otp-" + i, code.substring(i, i + 1));
        });
        assertThat(response.status()).isEqualTo(400);
        page.waitForFunction("() => document.body.innerText.includes('expired')");
    }

    private HttpSession session() {
        String id = context.cookies().stream().filter(c -> c.name.equals("JSESSIONID")).findFirst().orElseThrow().value;
        for (int i = 0; i < 100 && !sessions.values.containsKey(id); i++) page.waitForTimeout(10);
        assertThat(sessions.values).containsKey(id);
        return sessions.values.get(id);
    }

    static class SessionStore { final ConcurrentHashMap<String, HttpSession> values = new ConcurrentHashMap<>(); }
    @TestConfiguration static class SessionCapture {
        @Bean SessionStore sessionStore() { return new SessionStore(); }
        @Bean FilterRegistrationBean<Filter> captureSession(SessionStore store) {
            Filter filter = (request, response, chain) -> {
                // Pending-auth expiry needs a session even when anonymous login renders without one.
                if (((HttpServletRequest) request).getRequestURI().equals("/login")) {
                    ((HttpServletRequest) request).getSession(true);
                }
                chain.doFilter(request, response);
                HttpSession session = ((HttpServletRequest) request).getSession(false);
                if (session != null) store.values.put(session.getId(), session);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Integer.MIN_VALUE);
            return registration;
        }
    }
}
