package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.ChangePasswordService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChangePasswordController 단위 테스트")
class ChangePasswordControllerTest {

    @Mock ChangePasswordService  changePasswordService;
    @Mock UserDetailsService     userDetailsService;
    @Mock SecuritySettingService securitySettingService;

    @InjectMocks ChangePasswordController controller;

    @Mock HttpServletRequest request;
    @Mock HttpSession        session;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OswlUserPrincipal principal(Long userId, String email) {
        return new OswlUserPrincipal(
                userId, email, "hash", "Test User",
                false, true, List.of(), Set.of(), Set.of(), false);
    }

    private SecuritySetting securitySetting(int minLen) {
        return SecuritySetting.builder().minPasswordLength(minLen).build();
    }

    private ChangePasswordRequest req(String current, String newPw, String confirm) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setCurrentPassword(current);
        r.setNewPassword(newPw);
        r.setConfirmPassword(confirm);
        return r;
    }

    // ── principal null ─────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword: 인증 없음 → 401 반환")
    void changePassword_noPrincipal_returns401() {
        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "newPass1!", "newPass1!"), null, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── validation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword: 새 비밀번호가 minLen 미만 → 400")
    void changePassword_tooShort_returns400() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "short", "short"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).contains("8");
    }

    @Test
    @DisplayName("changePassword: 새 비밀번호와 확인 불일치 → 400")
    void changePassword_passwordMismatch_returns400() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "ValidPass1!", "DifferentPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).contains("do not match");
    }

    // ── service errors ────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword: 현재 비밀번호 틀림 → 400 + 적절한 메시지")
    void changePassword_wrongCurrentPassword_returns400() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));
        doThrow(new IllegalArgumentException("CURRENT_PASSWORD_WRONG"))
                .when(changePasswordService).changePassword(1L, "wrong", "newValidPass1!");

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("wrong", "newValidPass1!", "newValidPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).contains("current password");
    }

    @Test
    @DisplayName("changePassword: 새 비밀번호가 현재와 동일 → 400 + 적절한 메시지")
    void changePassword_sameAsCurrent_returns400() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));
        doThrow(new IllegalArgumentException("SAME_AS_CURRENT"))
                .when(changePasswordService).changePassword(1L, "SamePass1!", "SamePass1!");

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("SamePass1!", "SamePass1!", "SamePass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).contains("different");
    }

    @Test
    @DisplayName("changePassword: 알 수 없는 IAE → 500")
    void changePassword_unknownIAE_returns500() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));
        doThrow(new IllegalArgumentException("UNKNOWN_ERROR"))
                .when(changePasswordService).changePassword(anyLong(), any(), any());

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "ValidPass1!", "ValidPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("changePassword: 예상치 못한 예외 → 500")
    void changePassword_unexpectedException_returns500() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));
        doThrow(new RuntimeException("DB connection lost"))
                .when(changePasswordService).changePassword(anyLong(), any(), any());

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "ValidPass1!", "ValidPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── success ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword: 성공 → 200 + redirectUrl=/projects, 세션 교체")
    void changePassword_success_returns200() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        OswlUserPrincipal updated = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));
        doNothing().when(changePasswordService).changePassword(1L, "oldPass", "NewPass1!");
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(updated);
        when(request.getSession(false)).thenReturn(session);

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("oldPass", "NewPass1!", "NewPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("redirectUrl")).isEqualTo("/projects");
        verify(request).changeSessionId();
    }

    @Test
    @DisplayName("changePassword: 성공 시 session null이어도 정상 처리")
    void changePassword_success_sessionNull_noNPE() {
        OswlUserPrincipal p = principal(2L, "admin@test.com");
        OswlUserPrincipal updated = principal(2L, "admin@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(6));
        doNothing().when(changePasswordService).changePassword(2L, "old", "NewPass1!");
        when(userDetailsService.loadUserByUsername("admin@test.com")).thenReturn(updated);
        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<Map<String, String>> resp = controller.changePassword(
                req("old", "NewPass1!", "NewPass1!"), p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(request).changeSessionId();
    }

    @Test
    @DisplayName("changePassword: newPassword null → 빈 문자열 처리, minLen 미만 → 400")
    void changePassword_nullNewPassword_handlesGracefully() {
        OswlUserPrincipal p = principal(1L, "user@test.com");
        when(securitySettingService.getOrCreate()).thenReturn(securitySetting(8));

        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setCurrentPassword("old");
        r.setNewPassword(null);
        r.setConfirmPassword(null);

        ResponseEntity<Map<String, String>> resp = controller.changePassword(r, p, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
