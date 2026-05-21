package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.auth.service.TrustedDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 성공한 폼 로그인을 가로체서 설정된 2FA 모드를 적용한다.
 *
 * TwoFaMode = EMAIL_OTP일 때:
 *   1. 신뢰하는 기기(HMAC 쿠키 유효)면 직접 인증 완료.
 *   2. 그 외: 세션에 포늘링 2FA 상태를 저장하고, SecurityContext를 지우고,
 *      /login/otp-verify로 리다이렉트한다.
 *
 * 그 외: /projects로 정상 리다이렉트한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwoFaAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final SecuritySettingService securitySettingService;
    private final OtpService             otpService;
    private final TrustedDeviceService   trustedDeviceService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest  request,
                                        HttpServletResponse response,
                                        Authentication      authentication) throws IOException {

        SecuritySetting settings = securitySettingService.getOrCreate();

        if (settings.getTwoFaMode() == TwoFaMode.EMAIL_OTP) {
            OswlUserPrincipal principal = (OswlUserPrincipal) authentication.getPrincipal();

            // 기기가 신뢰되면 OTP 생략
            if (trustedDeviceService.isTrusted(principal.getUserId(), request)) {
                String dest = principal.isMustChangePassword() ? "/change-password" : "/projects";
                log.info("[Auth] 로그인 성공 user='{}' 신뢰 기기 우회 → {}", principal.getUsername(), dest);
                response.sendRedirect(request.getContextPath() + dest);
                return;
            }

            // Store pending 2FA state before clearing the security context
            HttpSession session = request.getSession(true);
            otpService.storePendingAuth(session, principal);

            // SecurityContext 제거 — 사용자가 아직 완전히 인증되지 않은 상태
            SecurityContextHolder.clearContext();
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

            log.info("[Auth] 로그인 성공 user='{}' → 2FA 채린지", principal.getUsername());
            response.sendRedirect(request.getContextPath() + "/login/otp-verify");
        } else {
            // 2FA 미설정 — 비밀번호 변경 필요 여부 확인
            OswlUserPrincipal principal = (OswlUserPrincipal) authentication.getPrincipal();
            String dest = principal.isMustChangePassword() ? "/change-password" : "/projects";
            log.info("[Auth] 로그인 성공 user='{}' (2FA 미사용) → {}", principal.getUsername(), dest);
            response.sendRedirect(request.getContextPath() + dest);
        }
    }
}
