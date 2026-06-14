package com.salkcoding.oswl.auth.controller;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthViewController 단위 테스트")
class AuthViewControllerTest {

    @Mock OtpService       otpService;
    @Mock HttpServletRequest request;
    @Mock HttpSession       session;
    @Mock Model             model;

    @InjectMocks AuthViewController controller;

    private OswlUserPrincipal principal(boolean mustChange) {
        return new OswlUserPrincipal(
                1L, "user@test.com", "hash", "Test User",
                false, true, List.of(), Set.of(), Set.of(), mustChange);
    }

    // ── loginPage ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("loginPage: auth/login 반환")
    void loginPage_returnsLoginView() {
        assertThat(controller.loginPage()).isEqualTo("auth/login");
    }

    // ── error views ───────────────────────────────────────────────────────

    @Test
    @DisplayName("forbidden: error/403 반환")
    void forbidden_returns403View() {
        assertThat(controller.forbidden()).isEqualTo("error/403");
    }

    @Test
    @DisplayName("unauthorized: error/401 반환")
    void unauthorized_returns401View() {
        assertThat(controller.unauthorized()).isEqualTo("error/401");
    }

    @Test
    @DisplayName("serverError: error/500 반환")
    void serverError_returns500View() {
        assertThat(controller.serverError()).isEqualTo("error/500");
    }

    @Test
    @DisplayName("serviceUnavailable: error/503 반환")
    void serviceUnavailable_returns503View() {
        assertThat(controller.serviceUnavailable()).isEqualTo("error/503");
    }

    // ── changePasswordPage ────────────────────────────────────────────────

    @Test
    @DisplayName("changePasswordPage: mustChangePassword=true → change-password 뷰")
    void changePasswordPage_mustChange_returnsView() {
        String view = controller.changePasswordPage(principal(true));

        assertThat(view).isEqualTo("auth/change-password");
    }

    @Test
    @DisplayName("changePasswordPage: mustChangePassword=false → /projects로 리다이렉트")
    void changePasswordPage_notMustChange_redirectsToProjects() {
        String view = controller.changePasswordPage(principal(false));

        assertThat(view).isEqualTo("redirect:/projects");
    }

    @Test
    @DisplayName("changePasswordPage: principal null → /projects로 리다이렉트")
    void changePasswordPage_nullPrincipal_redirectsToProjects() {
        String view = controller.changePasswordPage(null);

        assertThat(view).isEqualTo("redirect:/projects");
    }

    // ── otpVerifyPage ──────────────────────────────────────────────────────

    @Test
    @DisplayName("otpVerifyPage: session null → redirect:/login")
    void otpVerifyPage_sessionNull_redirectsToLogin() {
        when(request.getSession(false)).thenReturn(null);

        String view = controller.otpVerifyPage(request, model);

        assertThat(view).isEqualTo("redirect:/login");
    }

    @Test
    @DisplayName("otpVerifyPage: session 있지만 pending 없음 → redirect:/login")
    void otpVerifyPage_noPending_redirectsToLogin() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(false);

        String view = controller.otpVerifyPage(request, model);

        assertThat(view).isEqualTo("redirect:/login");
    }

    @Test
    @DisplayName("otpVerifyPage: pending OTP → otp-verify 뷰 + 모델 설정")
    void otpVerifyPage_pending_returnsOtpView() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.getPendingIdentity(session)).thenReturn(
                new com.salkcoding.oswl.auth.security.OtpPendingIdentity(1L, "user@test.com", "User", false));
        when(otpService.remainingSeconds(session)).thenReturn(120L);
        when(session.getAttribute(OtpService.SESSION_MAIL_FAILED)).thenReturn(null);

        String view = controller.otpVerifyPage(request, model);

        assertThat(view).isEqualTo("auth/otp-verify");
        verify(model).addAttribute(eq("maskedEmail"), anyString());
        verify(model).addAttribute(eq("expirySeconds"), eq(120L));
        verify(model).addAttribute(eq("mailFailed"), eq(false));
    }

    @Test
    @DisplayName("otpVerifyPage: mailFailed=true → 모델에 true 추가")
    void otpVerifyPage_mailFailed_addsTrueToModel() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.getPendingIdentity(session)).thenReturn(
                new com.salkcoding.oswl.auth.security.OtpPendingIdentity(1L, "user@test.com", "User", false));
        when(otpService.remainingSeconds(session)).thenReturn(60L);
        when(session.getAttribute(OtpService.SESSION_MAIL_FAILED)).thenReturn(Boolean.TRUE);

        controller.otpVerifyPage(request, model);

        verify(model).addAttribute(eq("mailFailed"), eq(true));
    }
}
