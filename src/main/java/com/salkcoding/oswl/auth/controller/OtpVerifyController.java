package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.TrustedDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Handles OTP code verification and resend requests during the 2FA login flow.
 *
 * POST /login/otp-verify
 *   Body  : { "code": "123456", "trustDevice": false }
 *   200   : { "redirectUrl": "/projects" }
 *   400   : { "message": "Invalid or expired code." }
 *   401   : { "message": "No pending verification." }
 *   423   : { "message": "Account locked." }
 *
 * POST /login/otp-resend
 *   200   : { "message": "Code resent." }
 *   401   : { "message": "No pending verification." }
 *   429   : { "message": "Please wait before requesting a new code." }
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OtpVerifyController {

    private final OtpService           otpService;
    private final AuditLogService       auditLogService;
    private final TrustedDeviceService  trustedDeviceService;

    @PostMapping("/login/otp-verify")
    public ResponseEntity<Map<String, String>> verifyOtp(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "No pending verification. Please log in again."));
        }

        String code = body.get("code") instanceof String s ? s.strip() : "";

        if (!otpService.verify(session, code)) {
            OswlUserPrincipal pendingPrincipal = otpService.getPendingPrincipal(session);
            String actorEmail = pendingPrincipal != null ? pendingPrincipal.getUsername() : "unknown";

            auditLogService.logAnonymous(actorEmail, "AUTH.OTP_FAILURE", "AUTH", null, null,
                    "IP: " + normalizeIp(request.getRemoteAddr()));

            if (otpService.isAccountLocked(session)) {
                session.invalidate();
                return ResponseEntity.status(423)
                        .body(Map.of("message", "Too many incorrect attempts. Your account has been locked. Please contact an administrator."));
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid or expired code. Please try again."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.clearPending(session);

        // Promote session to fully authenticated
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // Set trusted-device cookie if the user requested "remember this device"
        boolean trustDevice = Boolean.TRUE.equals(body.get("trustDevice"));
        if (trustDevice) {
            trustedDeviceService.setTrusted(principal.getUserId(), response);
        }

        log.info("[OTP] 2FA verification succeeded for user: {}", principal.getUsername());
        String redirectUrl = principal.isMustChangePassword() ? "/change-password" : "/projects";
        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }

    @PostMapping("/login/otp-resend")
    public ResponseEntity<?> resendOtp(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "No pending verification. Please log in again."));
        }

        if (!otpService.canResend(session)) {
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Please wait 60 seconds before requesting a new code."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.storePendingAuth(session, principal); // regenerates OTP + resets expiry

        boolean mailFailed = Boolean.TRUE.equals(session.getAttribute(OtpService.SESSION_MAIL_FAILED));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "Code resent.");
        body.put("mailFailed", mailFailed);
        return ResponseEntity.ok(body);
    }

    private static String normalizeIp(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        return ip;
    }
}
