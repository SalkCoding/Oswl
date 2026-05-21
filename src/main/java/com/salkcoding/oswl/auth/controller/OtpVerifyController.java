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
 * 2FA 로그인 플로우 중 OTP 코드 검증 및 재전송 요청을 처리한다.
 *
 * POST /login/otp-verify
 *   Body  : { "code": "123456", "trustDevice": false }
 *   200   : { "redirectUrl": "/projects" }
 *   400   : { "message": "코드가 유효하지 않거나 만료되었습니다." }
 *   401   : { "message": "대기 중인 인증이 없습니다." }
 *   423   : { "message": "계정이 잠겼습니다." }
 *
 * POST /login/otp-resend
 *   200   : { "message": "코드가 재전송되었습니다." }
 *   401   : { "message": "대기 중인 인증이 없습니다." }
 *   429   : { "message": "새 코드를 요청하기 전에 잠시 기다려 주세요." }
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
                    .body(Map.of("message", "대기 중인 인증이 없습니다. 다시 로그인하세요."));
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
                        .body(Map.of("message", "오류 횟수가 너무 많습니다. 계정이 잠겼습니다. 관리자에게 문의하세요."));
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "코드가 유효하지 않거나 만료되었습니다. 다시 시도하세요."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.clearPending(session);

        // 세션을 완전한 인증 상태로 승격
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // 사용자가 "이 장치 기억" 요청 시 신뢰장치 쿠키 설정
        boolean trustDevice = Boolean.TRUE.equals(body.get("trustDevice"));
        if (trustDevice) {
            trustedDeviceService.setTrusted(principal.getUserId(), response);
        }

        log.info("[OTP] 사용자 {}의 2FA 인증 성공.", principal.getUsername());
        String redirectUrl = principal.isMustChangePassword() ? "/change-password" : "/projects";
        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }

    @PostMapping("/login/otp-resend")
    public ResponseEntity<?> resendOtp(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session == null || !otpService.isPending(session)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "대기 중인 인증이 없습니다. 다시 로그인하세요."));
        }

        if (!otpService.canResend(session)) {
            return ResponseEntity.status(429)
                    .body(Map.of("message", "새 코드를 요청하기 전에 60초 기다려 주세요."));
        }

        OswlUserPrincipal principal = otpService.getPendingPrincipal(session);
        otpService.storePendingAuth(session, principal); // regenerates OTP + resets expiry

        boolean mailFailed = Boolean.TRUE.equals(session.getAttribute(OtpService.SESSION_MAIL_FAILED));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "코드가 재전송되었습니다.");
        body.put("mailFailed", mailFailed);
        return ResponseEntity.ok(body);
    }

    private static String normalizeIp(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        return ip;
    }
}
