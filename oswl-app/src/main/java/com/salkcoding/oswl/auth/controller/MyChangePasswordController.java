package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.ChangePasswordService;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoints for voluntary (user-initiated) password changes.
 *
 * <ul>
 *   <li>{@code POST /api/my/change-password/otp-verify} — verify step-up OTP when 2FA is enabled</li>
 *   <li>{@code POST /api/my/change-password/otp-resend} — resend step-up OTP</li>
 *   <li>{@code POST /api/my/change-password} — submit new password</li>
 * </ul>
 *
 * <p>When 2FA is enabled, the user must complete the step-up OTP challenge
 * (via {@code /my/change-password/otp}) before the password-change form is accessible
 * and before {@code POST /api/my/change-password} is accepted.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MyChangePasswordController {

    private final ChangePasswordService  changePasswordService;
    private final UserDetailsService     userDetailsService;
    private final SecuritySettingService securitySettingService;
    private final OtpService             otpService;

    // ── Step-up OTP verification ────────────────────────────────────────

    @PostMapping("/api/my/change-password/otp-verify")
    public ResponseEntity<Map<String, String>> verifyOtp(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "You are not authenticated."));
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(OtpService.SESSION_CHANGE_PW_OTP) == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "No pending OTP challenge. Please restart the flow."));
        }

        String code = body.get("code") instanceof String s ? s.strip() : "";

        if (!otpService.verifyChangePasswordOtp(session, code)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "The code is invalid or has expired. Please try again."));
        }

        otpService.markChangePasswordOtpVerified(session);
        log.info("[MyChangePassword] Step-up OTP verified for user '{}'.", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/my/change-password"));
    }

    @PostMapping("/api/my/change-password/otp-resend")
    public ResponseEntity<Map<String, String>> resendOtp(
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "You are not authenticated."));
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(OtpService.SESSION_CHANGE_PW_OTP) == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "No pending OTP challenge. Please restart the flow."));
        }

        if (!otpService.canResendChangePasswordOtp(session)) {
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Please wait 60 seconds before requesting a new code."));
        }

        otpService.storeChangePasswordOtp(session, principal.getUsername(), principal.getDisplayName());
        return ResponseEntity.ok(Map.of("message", "The code has been resent."));
    }

    // ── Voluntary password change ───────────────────────────────────────

    @PostMapping("/api/my/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "You are not authenticated."));
        }

        // If 2FA is enabled, require completed step-up OTP verification
        HttpSession session = request.getSession(false);
        if (securitySettingService.getOrCreate().getTwoFaMode() != TwoFaMode.DISABLED) {
            if (session == null || !otpService.isChangePasswordOtpVerified(session)) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "Identity verification required. Please complete OTP verification first."));
            }
        }

        // ── Input validation ──────────────────────────────────────────

        String currentPw = req.getCurrentPassword() != null ? req.getCurrentPassword() : "";
        String newPw     = req.getNewPassword()     != null ? req.getNewPassword()     : "";
        String confirmPw = req.getConfirmPassword() != null ? req.getConfirmPassword() : "";

        int minLen = securitySettingService.getOrCreate().getMinPasswordLength();
        if (newPw.length() < minLen) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "The new password must be at least " + minLen + " characters long."));
        }
        if (!newPw.equals(confirmPw)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Passwords do not match."));
        }

        // ── Business logic ────────────────────────────────────────────

        try {
            changePasswordService.voluntaryChangePassword(principal.getUserId(), currentPw, newPw);
        } catch (IllegalArgumentException e) {
            return switch (e.getMessage()) {
                case "CURRENT_PASSWORD_WRONG" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "The current password is incorrect."));
                case "SAME_AS_CURRENT" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "The new password must be different from the current password."));
                default -> {
                    log.error("[MyChangePassword] Unexpected error for user {}: {}", principal.getUsername(), e.getMessage());
                    yield ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
                }
            };
        } catch (Exception e) {
            log.error("[MyChangePassword] Error for user {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
        }

        // Clear the step-up OTP state
        if (session != null) {
            otpService.clearChangePasswordOtp(session);
        }

        // Rebuild principal in SecurityContext so any updated state is reflected
        OswlUserPrincipal updated =
                (OswlUserPrincipal) userDetailsService.loadUserByUsername(principal.getUsername());

        UsernamePasswordAuthenticationToken newAuth =
                new UsernamePasswordAuthenticationToken(updated, null, updated.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(newAuth);
        SecurityContextHolder.setContext(context);

        if (session != null) {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }

        // Rotate the session ID after credential changes to prevent session fixation
        request.changeSessionId();

        log.info("[MyChangePassword] Password successfully changed for user '{}'.", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/projects"));
    }
}
