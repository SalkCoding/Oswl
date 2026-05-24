package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.SetupRequest;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.RoleTemplateBootstrapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetupController 단위 테스트")
class SetupControllerTest {

    @Mock UserRepository                userRepository;
    @Mock PasswordEncoder               passwordEncoder;
    @Mock RoleTemplateBootstrapService  roleTemplateBootstrapService;
    @Mock AuditLogService               auditLogService;

    @InjectMocks SetupController controller;

    @Mock Model         model;
    @Mock BindingResult bindingResult;

    // ── GET /setup ──────────────────────────────────────────────────────

    @Test
    @DisplayName("setupForm: 이미 admin이 있으면 /login 리다이렉트")
    void setupForm_adminExists_redirectsToLogin() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(true);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("redirect:/login");
        verify(model, never()).addAttribute(anyString(), any());
    }

    @Test
    @DisplayName("setupForm: admin 없고 model에 setupRequest 없으면 추가 후 setup 뷰 반환")
    void setupForm_noAdmin_noAttribute_addsAndReturnsSetup() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(model.containsAttribute("setupRequest")).thenReturn(false);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("auth/setup");
        verify(model).addAttribute(eq("setupRequest"), any(SetupRequest.class));
    }

    @Test
    @DisplayName("setupForm: admin 없고 model에 setupRequest 이미 있으면 중복 추가 안 함")
    void setupForm_noAdmin_attributePresent_noDoubleAdd() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(model.containsAttribute("setupRequest")).thenReturn(true);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("auth/setup");
        verify(model, never()).addAttribute(anyString(), any());
    }

    // ── POST /setup ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createInitialAdmin: admin 이미 있으면 /login 리다이렉트")
    void createAdmin_adminExists_redirectsToLogin() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(true);

        SetupRequest req = new SetupRequest("Admin", "admin@test.com", "password1", "password1");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("redirect:/login");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("createInitialAdmin: 비밀번호 불일치 → bindingResult에 에러 추가 후 setup 뷰")
    void createAdmin_passwordMismatch_returnsSetupWithError() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(true);

        SetupRequest req = new SetupRequest("Admin", "admin@test.com", "pass1234", "different");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("auth/setup");
        verify(bindingResult).rejectValue(eq("passwordConfirm"), eq("mismatch"), anyString());
    }

    @Test
    @DisplayName("createInitialAdmin: 검증 오류(bindingResult.hasErrors=true) → setup 뷰")
    void createAdmin_validationErrors_returnsSetup() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(true);

        SetupRequest req = new SetupRequest("A", "a@b.com", "pass1234", "pass1234");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("auth/setup");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("createInitialAdmin: 유효한 요청 → 유저 저장, 감사 로그, /login?setup 리다이렉트")
    void createAdmin_validRequest_savesUserAndRedirects() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(passwordEncoder.encode("securePass1")).thenReturn("hashed-password");

        SetupRequest req = new SetupRequest("Admin User", "admin@test.com", "securePass1", "securePass1");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("redirect:/login?setup");
        verify(roleTemplateBootstrapService).ensureBuiltInTemplates();
        verify(userRepository).save(argThat(u ->
                "admin@test.com".equals(u.getEmail()) &&
                "hashed-password".equals(u.getPasswordHash()) &&
                "Admin User".equals(u.getDisplayName()) &&
                u.isSystemAdmin()
        ));
        verify(auditLogService).logAnonymous(
                eq("admin@test.com"), eq("SYSTEM.SETUP"), eq("SYSTEM"),
                isNull(), eq("Admin User"), isNull());
    }

    @Test
    @DisplayName("createInitialAdmin: 이메일은 trim+소문자로 저장")
    void createAdmin_emailTrimmedAndLowercased() {
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        SetupRequest req = new SetupRequest("Admin", "  ADMIN@Test.Com  ", "pass1234!", "pass1234!");
        controller.createInitialAdmin(req, bindingResult, model);

        verify(userRepository).save(argThat(u -> "admin@test.com".equals(u.getEmail())));
    }
}
