package com.salkcoding.oswl.auth.controller;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.dto.SetupRequest;
import com.salkcoding.oswl.auth.entity.InstanceSetupLock;
import com.salkcoding.oswl.auth.repository.InstanceSetupLockRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.PasswordPolicyService;
import com.salkcoding.oswl.auth.service.RoleTemplateBootstrapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("SetupController 단위 테스트")
class SetupControllerTest {

    @Mock UserRepository                userRepository;
    @Mock InstanceSetupLockRepository   setupLockRepository;
    @Mock PasswordEncoder               passwordEncoder;
    @Mock RoleTemplateBootstrapService  roleTemplateBootstrapService;
    @Mock AuditLogService               auditLogService;
    @Mock PasswordPolicyService          passwordPolicyService;

    @InjectMocks SetupController controller;

    @Mock Model         model;
    @Mock BindingResult bindingResult;

    // ── GET /setup ──────────────────────────────────────────────────────

    @Test
    @DisplayName("setupForm: 이미 admin이 있으면 /login 리다이렉트")
    void setupForm_adminExists_redirectsToLogin() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(true);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("redirect:/login");
        verify(model, never()).addAttribute(anyString(), any());
    }

    @Test
    @DisplayName("setupForm: admin 없고 model에 setupRequest 없으면 추가 후 setup 뷰 반환")
    void setupForm_noAdmin_noAttribute_addsAndReturnsSetup() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(model.containsAttribute("setupRequest")).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(12);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("auth/setup");
        verify(model).addAttribute(eq("setupRequest"), any(SetupRequest.class));
        verify(model).addAttribute("minPasswordLength", 12);
    }

    @Test
    @DisplayName("setupForm: admin 없고 model에 setupRequest 이미 있으면 중복 추가 안 함")
    void setupForm_noAdmin_attributePresent_noDoubleAdd() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(model.containsAttribute("setupRequest")).thenReturn(true);

        String view = controller.setupForm(model);

        assertThat(view).isEqualTo("auth/setup");
        verify(model, never()).addAttribute(eq("setupRequest"), any());
    }

    // ── POST /setup ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createInitialAdmin: admin 이미 있으면 /login 리다이렉트")
    void createAdmin_adminExists_redirectsToLogin() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(true);

        SetupRequest req = new SetupRequest("Admin", "admin@test.com", "password1", "password1");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("redirect:/login");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("createInitialAdmin: 비밀번호 불일치 → bindingResult에 에러 추가 후 setup 뷰")
    void createAdmin_passwordMismatch_returnsSetupWithError() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(8);
        when(bindingResult.hasErrors()).thenReturn(true);

        SetupRequest req = new SetupRequest("Admin", "admin@test.com", "pass1234", "different");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("auth/setup");
        verify(bindingResult).rejectValue(eq("passwordConfirm"), eq("mismatch"), anyString());
    }

    @Test
    @DisplayName("createInitialAdmin: 검증 오류(bindingResult.hasErrors=true) → setup 뷰")
    void createAdmin_validationErrors_returnsSetup() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(8);
        when(bindingResult.hasErrors()).thenReturn(true);

        SetupRequest req = new SetupRequest("A", "a@b.com", "pass1234", "pass1234");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("auth/setup");
        verify(userRepository, never()).save(any());
        verify(setupLockRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("createInitialAdmin: 유효한 요청 → lock + 유저 저장, 감사 로그, /login?setup 리다이렉트")
    void createAdmin_validRequest_savesUserAndRedirects() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(8);
        when(passwordEncoder.encode("securePass1")).thenReturn("hashed-password");
        when(setupLockRepository.saveAndFlush(any())).thenReturn(InstanceSetupLock.create());

        SetupRequest req = new SetupRequest("Admin User", "admin@test.com", "securePass1", "securePass1");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("redirect:/login?setup");
        verify(passwordPolicyService).validateMinLength(eq("securePass1"), eq(bindingResult), eq("password"));
        verify(setupLockRepository).saveAndFlush(any(InstanceSetupLock.class));
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
    @DisplayName("createInitialAdmin: 동시 setup( lock 충돌 ) → setup 뷰 + global 에러")
    void createAdmin_lockConflict_returnsSetupWithGlobalError() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(8);
        when(setupLockRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        SetupRequest req = new SetupRequest("Late User", "late@test.com", "securePass1", "securePass1");
        String view = controller.createInitialAdmin(req, bindingResult, model);

        assertThat(view).isEqualTo("auth/setup");
        verify(bindingResult).reject(eq("auth.setup.error.alreadyCompleted"), anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("createInitialAdmin: 이메일은 trim+소문자로 저장")
    void createAdmin_emailTrimmedAndLowercased() {
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(passwordPolicyService.getMinLength()).thenReturn(8);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(setupLockRepository.saveAndFlush(any())).thenReturn(InstanceSetupLock.create());

        SetupRequest req = new SetupRequest("Admin", "  ADMIN@Test.Com  ", "pass1234!", "pass1234!");
        controller.createInitialAdmin(req, bindingResult, model);

        verify(userRepository).save(argThat(u -> "admin@test.com".equals(u.getEmail())));
    }
}
