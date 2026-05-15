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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Intercepts successful form-login and enforces the configured 2FA mode.
 *
 * If TwoFaMode = EMAIL_OTP:
 *   1. If the device is trusted (valid HMAC cookie) → complete auth directly.
 *   2. Otherwise: stores the authenticated principal in the session as "pending 2FA",
 *      clears the SecurityContext, and redirects to /login/otp-verify.
 *
 * Otherwise: redirects to /projects as normal.
 */
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

            // Skip OTP if the device is already trusted
            if (trustedDeviceService.isTrusted(principal.getUserId(), request)) {
                response.sendRedirect(request.getContextPath() + "/projects");
                return;
            }

            // Store pending 2FA state before clearing the security context
            HttpSession session = request.getSession(true);
            otpService.storePendingAuth(session, principal);

            // Clear the context — the user is not fully authenticated yet
            SecurityContextHolder.clearContext();
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

            response.sendRedirect(request.getContextPath() + "/login/otp-verify");
        } else {
            // No 2FA configured — proceed normally
            response.sendRedirect(request.getContextPath() + "/projects");
        }
    }
}
