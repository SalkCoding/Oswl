package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.LoginCompletionService;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.TrustedDeviceService;
import org.springframework.security.core.session.SessionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpVerifyController 단위 테스트")
class OtpVerifyControllerTest {

    @Mock OtpService              otpService;
    @Mock AuditLogService         auditLogService;
    @Mock TrustedDeviceService    trustedDeviceService;
    @Mock SessionRegistry         sessionRegistry;
    @Mock LoginCompletionService  loginCompletionService;

    @InjectMocks OtpVerifyController controller;

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;
    @Mock HttpSession          session;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        lenient().when(session.getId()).thenReturn("session-old");
        lenient().when(sessionRegistry.getSessionInformation("session-old")).thenReturn(null);
        lenient().when(request.getSession(true)).thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OswlUserPrincipal principal(Long userId, String email, boolean mustChange) {
        return new OswlUserPrincipal(
                userId, email, "hash", "Display Name",
                false, true, List.of(), Set.of(), Set.of(), mustChange);
    }

    // ── verifyOtp ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("verifyOtp: session null → 401 반환")
    void verifyOtp_sessionNull_returns401() {
        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "123456"), request, response);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("message")).contains("pending authentication");
    }

    @Test
    @DisplayName("verifyOtp: session 있지만 pending 없음 → 401 반환")
    void verifyOtp_noPending_returns401() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "000000"), request, response);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("verifyOtp: OTP 검증 실패 → 400, 감사 로그 기록")
    void verifyOtp_verificationFails_returns400AndLogs() {
        OswlUserPrincipal p = principal(1L, "user@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "999999")).thenReturn(false);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);
        when(otpService.isAccountLocked(session)).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "999999"), request, response);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).contains("invalid");
        verify(auditLogService).logAnonymous(eq("user@test.com"), eq("AUTH.OTP_FAILURE"), any(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("verifyOtp: 실패 횟수 초과로 잠김 → 423 반환, 세션 무효화")
    void verifyOtp_accountLocked_returns423() {
        OswlUserPrincipal p = principal(1L, "locked@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "111111")).thenReturn(false);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);
        when(otpService.isAccountLocked(session)).thenReturn(true);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "111111"), request, response);

        assertThat(resp.getStatusCode().value()).isEqualTo(423);
        assertThat(resp.getBody().get("message")).contains("locked");
        verify(session).invalidate();
    }

    @Test
    @DisplayName("verifyOtp: 성공 → 200 + redirectUrl=/projects")
    void verifyOtp_success_redirectsToProjects() {
        OswlUserPrincipal p = principal(1L, "ok@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "123456")).thenReturn(true);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "123456", "trustDevice", false), request, response);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("redirectUrl")).isEqualTo("/projects");
        verify(otpService).clearPending(session);
        verify(trustedDeviceService, never()).setTrusted(anyLong(), any(), any());
    }

    @Test
    @DisplayName("verifyOtp: 성공 + trustDevice=true → 신뢰 디바이스 쿠키 설정")
    void verifyOtp_successWithTrustDevice_setsTrustedCookie() {
        OswlUserPrincipal p = principal(2L, "ok@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "654321")).thenReturn(true);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);

        controller.verifyOtp(Map.of("code", "654321", "trustDevice", true), request, response);

        verify(trustedDeviceService).setTrusted(eq(2L), eq(request), eq(response));
    }

    @Test
    @DisplayName("verifyOtp: mustChangePassword=true → redirectUrl=/change-password")
    void verifyOtp_mustChangePassword_redirectsToChangePassword() {
        OswlUserPrincipal p = principal(3L, "forced@test.com", true);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "111111")).thenReturn(true);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);

        ResponseEntity<Map<String, String>> resp = controller.verifyOtp(
                Map.of("code", "111111"), request, response);

        assertThat(resp.getBody().get("redirectUrl")).isEqualTo("/change-password");
    }

    @Test
    @DisplayName("verifyOtp: pendingPrincipal null이면 actorEmail='unknown'으로 로그")
    void verifyOtp_nullPrincipal_logsUnknown() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.verify(session, "000000")).thenReturn(false);
        when(otpService.getPendingPrincipal(session)).thenReturn(null);
        when(otpService.isAccountLocked(session)).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("::1");

        controller.verifyOtp(Map.of("code", "000000"), request, response);

        verify(auditLogService).logAnonymous(eq("unknown"), any(), any(), isNull(), isNull(), any());
    }

    // ── resendOtp ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("resendOtp: session null → 401 반환")
    void resendOtp_sessionNull_returns401() {
        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<?> resp = controller.resendOtp(request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("resendOtp: pending 없음 → 401 반환")
    void resendOtp_noPending_returns401() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(false);

        ResponseEntity<?> resp = controller.resendOtp(request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("resendOtp: canResend=false → 429 반환")
    void resendOtp_cannotResend_returns429() {
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.canResend(session)).thenReturn(false);

        ResponseEntity<?> resp = controller.resendOtp(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("resendOtp: 성공 → 200 + message 반환")
    void resendOtp_success_returns200() {
        OswlUserPrincipal p = principal(1L, "user@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.canResend(session)).thenReturn(true);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);
        when(session.getAttribute(OtpService.SESSION_MAIL_FAILED)).thenReturn(null);

        ResponseEntity<?> resp = controller.resendOtp(request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("message")).isEqualTo("The code has been resent.");
        verify(otpService).storePendingAuth(session, p);
    }

    @Test
    @DisplayName("resendOtp: mailFailed=true → mailFailed 플래그 포함")
    void resendOtp_mailFailed_includesFlag() {
        OswlUserPrincipal p = principal(1L, "user@test.com", false);
        when(request.getSession(false)).thenReturn(session);
        when(otpService.isPending(session)).thenReturn(true);
        when(otpService.canResend(session)).thenReturn(true);
        when(otpService.getPendingPrincipal(session)).thenReturn(p);
        when(session.getAttribute(OtpService.SESSION_MAIL_FAILED)).thenReturn(true);

        ResponseEntity<?> resp = controller.resendOtp(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("mailFailed")).isEqualTo(true);
    }
}
