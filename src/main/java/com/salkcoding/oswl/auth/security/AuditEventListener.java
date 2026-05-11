package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Spring Security 인증 이벤트를 감지해 감사 로그를 기록한다.
 *
 *  - AUTH.LOGIN_SUCCESS : 로그인 성공
 *  - AUTH.LOGIN_FAILURE : 로그인 실패 (비밀번호 오류 / 계정 비활성 포함)
 *
 * 로그아웃 이벤트는 AuditLogoutSuccessHandler 에서 처리한다.
 */
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    @EventListener
    @Transactional
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        userRepository.updateLastLoginAt(email, LocalDateTime.now());
        auditLogService.log("AUTH.LOGIN_SUCCESS", "AUTH", null, null, null);
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String attempted = event.getAuthentication().getName();
        String reason    = event.getException().getClass().getSimpleName()
                .replace("Exception", "").replace("Authentication", "");
        auditLogService.logAnonymous(attempted, "AUTH.LOGIN_FAILURE", "AUTH", null, null, "Attempted: " + attempted + " / Reason: " + reason);
    }
}
