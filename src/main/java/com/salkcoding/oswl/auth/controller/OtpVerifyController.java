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
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
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
 *   400   : { "message": "The code is invalid or has expired." }
 *   401   : { "message": "There is no pending authentication." }
 *   423   : { "message": "The account is locked." }
 *
 * POST /login/otp-resend
 *   200   : { "message": "The code has been resent." }
 *   401   : { "message": "There is no pending authentication." }
 *   429   : { "message": "Please wait a moment before requesting a new code." }
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OtpVerifyController {

    private final OtpService           otpService;
    private final AuditLogService       auditLogService;
    private final TrustedDeviceService  trustedDeviceService;
    private final SessionRegistry       sessionRegistry;

    @PostMapping("/login/otp-verify")
    public ResponseEntity<Map<String, String>> verifyOtp(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "There is no pending authentication. Please sign in again."));
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
                        .body(Map.of("message", "Too many failed attempts. The account has been locked. Please contact an administrator."));
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "The code is invalid or has expired. Please try again."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.clearPending(session);

        // Reject OTP if this session was displaced by a concurrent login while OTP was pending
        SessionInformation sessionInfo = sessionRegistry.getSessionInformation(session.getId());
        if (sessionInfo != null && sessionInfo.isExpired()) {
            session.invalidate();
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Your session was displaced by a concurrent login. Please sign in again."));
        }

        // Promote the session to a fully authenticated state
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        // Session fixation protection: replace the pending-auth session with a fresh session,
        // then explicitly update SessionRegistry (belt-and-suspenders for in-memory registry).
        String oldSessionId = session.getId();
        session.invalidate();
        HttpSession newSession = request.getSession(true);
        newSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessionRegistry.removeSessionInformation(oldSessionId);
        sessionRegistry.registerNewSession(newSession.getId(), principal);

        // Set the trusted-device cookie when the user requests "remember this device"
        boolean trustDevice = Boolean.TRUE.equals(body.get("trustDevice"));
        if (trustDevice) {
            trustedDeviceService.setTrusted(principal.getUserId(), response);
        }

        log.info("[OTP] 2FA authentication succeeded for user {}.", principal.getUsername());
        String redirectUrl = principal.isMustChangePassword() ? "/change-password" : "/projects";
        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }

    @PostMapping("/login/otp-resend")
    public ResponseEntity<?> resendOtp(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "There is no pending authentication. Please sign in again."));
        }

        if (!otpService.canResend(session)) {
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Please wait 60 seconds before requesting a new code."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.storePendingAuth(session, principal); // regenerates OTP + resets expiry

        boolean mailFailed = Boolean.TRUE.equals(session.getAttribute(OtpService.SESSION_MAIL_FAILED));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "The code has been resent.");
        body.put("mailFailed", mailFailed);
        return ResponseEntity.ok(body);
    }

    private static String normalizeIp(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        return ip;
    }
}
