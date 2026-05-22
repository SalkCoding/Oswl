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
 * Intercepts successful form login and applies the configured 2FA mode.
 *
 * When TwoFaMode = EMAIL_OTP:
 *   1. If the device is trusted (valid HMAC cookie), complete authentication directly.
 *   2. Otherwise, store pending 2FA state in the session, clear the SecurityContext,
 *      and redirect to /login/otp-verify.
 *
 * Otherwise, redirect normally to /projects.
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

            // Skip OTP when the device is trusted
            if (trustedDeviceService.isTrusted(principal.getUserId(), request)) {
                String dest = principal.isMustChangePassword() ? "/change-password" : "/projects";
                log.info("[Auth] Login succeeded for user='{}' via trusted-device bypass → {}", principal.getUsername(), dest);
                response.sendRedirect(request.getContextPath() + dest);
                return;
            }

            // Store pending 2FA state before clearing the security context
            HttpSession session = request.getSession(true);
            otpService.storePendingAuth(session, principal);

            // Clear the SecurityContext — the user is not yet fully authenticated
            SecurityContextHolder.clearContext();
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

            log.info("[Auth] Login succeeded for user='{}' → 2FA challenge", principal.getUsername());
            response.sendRedirect(request.getContextPath() + "/login/otp-verify");
        } else {
            // 2FA not configured — check whether a password change is required
            OswlUserPrincipal principal = (OswlUserPrincipal) authentication.getPrincipal();
            String dest = principal.isMustChangePassword() ? "/change-password" : "/projects";
            log.info("[Auth] Login succeeded for user='{}' (2FA disabled) → {}", principal.getUsername(), dest);
            response.sendRedirect(request.getContextPath() + dest);
        }
    }
}
