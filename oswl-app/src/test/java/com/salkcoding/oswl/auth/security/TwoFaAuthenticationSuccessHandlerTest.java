package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.service.LoginCompletionService;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.auth.service.TrustedDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("TwoFaAuthenticationSuccessHandler 단위 테스트")
class TwoFaAuthenticationSuccessHandlerTest {

    @Mock SecuritySettingService  securitySettingService;
    @Mock OtpService              otpService;
    @Mock TrustedDeviceService    trustedDeviceService;
    @Mock LoginCompletionService  loginCompletionService;

    @InjectMocks TwoFaAuthenticationSuccessHandler handler;

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;
    @Mock Authentication      authentication;

    private OswlUserPrincipal buildPrincipal(Long userId, boolean mustChangePassword) {
        return new OswlUserPrincipal(
                userId, "user@test.com", "hash", "Test User",
                false, true,
                List.of(), Set.of(), Set.of(),
                mustChangePassword);
    }

    // ── 2FA disabled ────────────────────────────────────────────────────

    @Test
    @DisplayName("2FA DISABLED + mustChangePassword=false → /projects 리다이렉트")
    void twoFaDisabled_notMustChange_redirectsToProjects() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.DISABLED).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(authentication.getPrincipal()).thenReturn(buildPrincipal(1L, false));
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/projects");
        verify(otpService, never()).storePendingAuth(any(), any());
    }

    @Test
    @DisplayName("2FA DISABLED + mustChangePassword=true → /change-password 리다이렉트")
    void twoFaDisabled_mustChange_redirectsToChangePassword() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.DISABLED).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(authentication.getPrincipal()).thenReturn(buildPrincipal(1L, true));
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/change-password");
    }

    @Test
    @DisplayName("2FA DISABLED, contextPath=/app → 경로 포함하여 리다이렉트")
    void twoFaDisabled_withContextPath_includesContextPath() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.DISABLED).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(authentication.getPrincipal()).thenReturn(buildPrincipal(1L, false));
        when(request.getContextPath()).thenReturn("/app");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/app/projects");
    }

    // ── 2FA EMAIL_OTP: trusted device ──────────────────────────────────

    @Test
    @DisplayName("EMAIL_OTP + trusted device + not mustChange → /projects 리다이렉트")
    void emailOtp_trustedDevice_redirectsToProjects() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.EMAIL_OTP).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        OswlUserPrincipal principal = buildPrincipal(5L, false);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(trustedDeviceService.isTrusted(5L, request)).thenReturn(true);
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(loginCompletionService).recordSuccessfulLogin("user@test.com");
        verify(response).sendRedirect("/projects");
        verify(otpService, never()).storePendingAuth(any(), any());
    }

    @Test
    @DisplayName("EMAIL_OTP + trusted device + mustChange=true → /change-password 리다이렉트")
    void emailOtp_trustedDevice_mustChange_redirectsToChangePassword() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.EMAIL_OTP).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        OswlUserPrincipal principal = buildPrincipal(5L, true);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(trustedDeviceService.isTrusted(5L, request)).thenReturn(true);
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/change-password");
    }

    // ── 2FA EMAIL_OTP: not trusted device ──────────────────────────────

    @Test
    @DisplayName("EMAIL_OTP + not trusted → OTP challenge, /login/otp-verify 리다이렉트")
    void emailOtp_notTrusted_redirectsToOtpVerify() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.EMAIL_OTP).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        OswlUserPrincipal principal = buildPrincipal(7L, false);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(trustedDeviceService.isTrusted(7L, request)).thenReturn(false);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(otpService).storePendingAuth(session, principal);
        verify(response).sendRedirect("/login/otp-verify");
    }

    @Test
    @DisplayName("EMAIL_OTP + not trusted, contextPath=/app → /app/login/otp-verify 리다이렉트")
    void emailOtp_notTrusted_withContextPath_includesContextPath() throws IOException {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L).twoFaMode(TwoFaMode.EMAIL_OTP).build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        OswlUserPrincipal principal = buildPrincipal(7L, false);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(trustedDeviceService.isTrusted(7L, request)).thenReturn(false);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);
        when(request.getContextPath()).thenReturn("/app");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/app/login/otp-verify");
    }
}
