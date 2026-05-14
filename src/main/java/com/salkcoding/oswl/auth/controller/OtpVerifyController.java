package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
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
 *
 * POST /login/otp-resend
 *   200   : { "message": "Code resent." }
 *   401   : { "message": "No pending verification." }
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OtpVerifyController {

    private final OtpService otpService;

    @PostMapping("/login/otp-verify")
    public ResponseEntity<Map<String, String>> verifyOtp(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "No pending verification. Please log in again."));
        }

        String code = body.get("code") instanceof String s ? s.strip() : "";

        if (!otpService.verify(session, code)) {
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

        log.info("[OTP] 2FA verification succeeded for user: {}", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/projects"));
    }

    @PostMapping("/login/otp-resend")
    public ResponseEntity<Map<String, String>> resendOtp(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "No pending verification. Please log in again."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.storePendingAuth(session, principal); // regenerates OTP + resets expiry

        return ResponseEntity.ok(Map.of("message", "Code resent."));
    }
}
