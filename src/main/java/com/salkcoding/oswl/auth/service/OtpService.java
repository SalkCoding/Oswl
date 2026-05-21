package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Email OTP 2단계 인증 흐름의 세션 내 상태를 관리한다.
 *
 * 흐름:
 *  1. 자격증명 성공 후 TwoFaAuthenticationSuccessHandler가
 *     storePendingAuth()를 호출하여 세션에 principal + 일회성 코드를 저장한다.
 *  2. 브라우저는 /login/otp-verify로 리다이렉트된다.
 *  3. OtpVerifyController는 verify()를 통해 제출된 코드를 검증한다.
 *     성공 시 세션을 완전 인증 상태로 승격시킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    // ── 세션 속성 키 ────────────────────────────────────────────
    public static final String SESSION_PRINCIPAL   = "PENDING_2FA_PRINCIPAL";
    public static final String SESSION_OTP         = "PENDING_2FA_OTP";
    public static final String SESSION_EXPIRY      = "PENDING_2FA_EXPIRY_MS";
    public static final String SESSION_ATTEMPTS    = "PENDING_2FA_ATTEMPTS";
    public static final String SESSION_LAST_SENT   = "PENDING_2FA_LAST_SENT";
    public static final String SESSION_LOCKED      = "PENDING_2FA_LOCKED";
    public static final String SESSION_MAIL_FAILED = "PENDING_2FA_MAIL_FAILED";

    private static final int  OTP_VALID_MINUTES  = 3;
    private static final int  MAX_DIGITS         = 1_000_000; // 000000–999999
    private static final int  MAX_OTP_ATTEMPTS   = 5;
    private static final long RESEND_COOLDOWN_MS = 60_000L;   // 60 seconds

    /**
     * TODO: 프로덕션 배포 전에 테스트 우회를 제거할 것.
     *       로컈 개발용으로 "000000"은 항상 유효한 OTP로 인정된다.
     */
    private static final String TEST_BYPASS_CODE = "000000";

    private final MailService    mailService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final SecureRandom   secureRandom = new SecureRandom();

    // ── 공개 API ────────────────────────────────────────────────────────

    /**
     * 6자리 OTP를 생성하여 세션에 저장한 후 사용자의 등록된 이메일로 전송한다.
     *
     * SecuritySetting에서 메일이 DISABLED일 때는 콘솔 로깅으로 대체한다
     * (개발 편의 기능).
     */
    public void storePendingAuth(HttpSession session, OswlUserPrincipal principal) {
        String otp     = String.format("%06d", secureRandom.nextInt(MAX_DIGITS));
        long   expiry  = Instant.now().plusSeconds(60L * OTP_VALID_MINUTES).toEpochMilli();

        session.setAttribute(SESSION_PRINCIPAL, principal);
        session.setAttribute(SESSION_OTP,       otp);
        session.setAttribute(SESSION_EXPIRY,    expiry);
        session.setAttribute(SESSION_LAST_SENT, Instant.now().toEpochMilli());

        String email = principal.getUsername(); // username == email
        String name  = principal.getDisplayName();
        try {
            mailService.sendOtp(email, name, otp);
            session.removeAttribute(SESSION_MAIL_FAILED);
            log.info("[OTP] user='{}' OTP 발급 완료, 유효={}min", email, OTP_VALID_MINUTES);
        } catch (Exception e) {
            // 전송 실패 시도 인증 흐름을 막지 않음;
            // OTP는 세션에 여전히 저장되어 있음.
            session.setAttribute(SESSION_MAIL_FAILED, true);
            log.error("[OTP] '{}' OTP 이메일 전송 실패: {}", email, e.getMessage());
            // DEV 폴백: SMTP 미설정 시도 로컈 테스트를 위해 코드를 출력
            log.debug("[OTP][DEV-FALLBACK] OTP 이메일 전송 실패 — '{}' 코드: {}", email, otp);
        }
    }

    /**
     * 세션에 포뉲 2FA 항목이 있는 경우(=비밀번호 인증은 되었지만
     * OTP 검증은 안 된 상태) true를 반환한다.
     */
    public boolean isPending(HttpSession session) {
        return session.getAttribute(SESSION_PRINCIPAL) != null;
    }

    /**
     * 마지막 OTP 전송 이후 {@value RESEND_COOLDOWN_MS}ms 이상 지난 경우 true를 반환한다.
     */
    public boolean canResend(HttpSession session) {
        Long lastSent = (Long) session.getAttribute(SESSION_LAST_SENT);
        if (lastSent == null) return true;
        return (Instant.now().toEpochMilli() - lastSent) >= RESEND_COOLDOWN_MS;
    }

    /**
     * OTP 실패 횟수 초과로 계정이 잠긴 경우 true를 반환한다.
     */
    public boolean isAccountLocked(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(SESSION_LOCKED));
    }

    /**
     * 제출된 OTP 코드를 검증한다.
     * 실패 시 시도 횟수를 증가하며; {@value MAX_OTP_ATTEMPTS}회에 이르면 계정을 잠근다.
     *
     * @param session 현재 HTTP 세션
     * @param code    사용자가 입력한 6자리 코드
     * @return 코드가 올바르고 아직 만료되지 않았으면 true
     */
    public boolean verify(HttpSession session, String code) {
        if (code == null) return false;

        // TODO: 테스트 우회 제거 — "000000"은 로컈 개발용으로만 통과
        if (TEST_BYPASS_CODE.equals(code)) {
            OswlUserPrincipal p = getPendingPrincipal(session);
            log.warn("[OTP][테스트용] 테스트 우회 '000000' 사용자 '{}' 수락",
                    p != null ? p.getUsername() : "unknown");
            return true;
        }

        String stored = (String) session.getAttribute(SESSION_OTP);
        Long   expiry = (Long)   session.getAttribute(SESSION_EXPIRY);

        if (stored == null || expiry == null) return false;
        if (Instant.now().toEpochMilli() > expiry)  return false;

        if (stored.equals(code)) {
            OswlUserPrincipal verified = getPendingPrincipal(session);
            log.info("[OTP] user='{}' OTP 검증 성공", verified != null ? verified.getUsername() : "unknown");
            return true;
        }

        // 잘못된 코드 — 시도 횟수 추적
        Integer existing = (Integer) session.getAttribute(SESSION_ATTEMPTS);
        int attempts = (existing == null ? 0 : existing) + 1;
        session.setAttribute(SESSION_ATTEMPTS, attempts);

        OswlUserPrincipal failed = getPendingPrincipal(session);
        log.warn("[OTP] user='{}' 잘못된 코드 {}/{} 시도",
                attempts, MAX_OTP_ATTEMPTS, failed != null ? failed.getUsername() : "unknown");

        if (attempts >= MAX_OTP_ATTEMPTS) {
            session.setAttribute(SESSION_LOCKED, true);
            lockAccount(session);
        }
        return false;
    }

    /** 인증됨(세션 포뉨)지만 2FA 미완료 상태의 principal을 반환하거나 null. */
    public OswlUserPrincipal getPendingPrincipal(HttpSession session) {
        return (OswlUserPrincipal) session.getAttribute(SESSION_PRINCIPAL);
    }

    /** 남은 유효 시간(초 단위)을 반환한다(만료 또는 미설정 시 0). */
    public long remainingSeconds(HttpSession session) {
        Long expiry = (Long) session.getAttribute(SESSION_EXPIRY);
        if (expiry == null) return 0;
        return Math.max(0, (expiry - Instant.now().toEpochMilli()) / 1000);
    }

    /** 세션에서 모든 포뉨 2FA 속성을 제거한다. */
    public void clearPending(HttpSession session) {
        session.removeAttribute(SESSION_PRINCIPAL);
        session.removeAttribute(SESSION_OTP);
        session.removeAttribute(SESSION_EXPIRY);
        session.removeAttribute(SESSION_ATTEMPTS);
        session.removeAttribute(SESSION_LAST_SENT);
        session.removeAttribute(SESSION_LOCKED);
    }

    // ── 비공개 헬퍼 ───────────────────────────────────────────────────

    @Transactional
    private void lockAccount(HttpSession session) {
        OswlUserPrincipal principal = getPendingPrincipal(session);
        if (principal == null) return;

        String email = principal.getUsername();
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isEnabled()) {
                user.setEnabled(false);
                auditLogService.logAnonymous(email, "USER.DEACTIVATE", "USER",
                        user.getId().toString(), email,
                        "OTP " + MAX_OTP_ATTEMPTS + "회 실패로 자동 잠김");
                log.warn("[OTP] 계정 '{}' OTP {}회 실패로 잠김", email, MAX_OTP_ATTEMPTS);
            }
        });
    }
}
